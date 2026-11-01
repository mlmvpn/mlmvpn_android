//! TLS 1.3 (rustls + ring) for phone <-> phone, over TCP and inside QUIC.
//!
//! Each session makes a fresh self-signed certificate. Its SHA-256 goes into the phone's card;
//! the other phone accepts exactly that certificate and nothing else (pinning). No SNI is sent,
//! and the ALPN value is derived from the session id, so the handshake names neither the app
//! nor the person.

use crate::util::{b64e, sha256};
use anyhow::{anyhow, Result};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::CryptoProvider;
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, ServerConfig, SignatureScheme};
use std::sync::Arc;
use std::time::Duration;

pub struct Ident {
    pub cert: CertificateDer<'static>,
    key: Vec<u8>,
    pub fp: [u8; 32],
}

impl Ident {
    pub fn new() -> Result<Self> {
        let ck = rcgen::generate_simple_self_signed(vec!["localhost".to_string()])?;
        let cert = ck.cert.der().clone();
        let key = ck.key_pair.serialize_der();
        let fp = sha256(&[cert.as_ref()]);
        Ok(Ident { cert, key, fp })
    }

    pub fn fp_b64(&self) -> String {
        b64e(&self.fp)
    }

    fn key(&self) -> PrivateKeyDer<'static> {
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.key.clone()))
    }
}

pub fn provider() -> Arc<CryptoProvider> {
    Arc::new(rustls::crypto::ring::default_provider())
}

/// The ALPN a server with session id `sid` expects. Random-looking, the same on both sides.
pub fn alpn_for(sid: &str) -> Vec<u8> {
    let h = sha256(&[b"mlmnp/alpn/1", sid.as_bytes()]);
    hex::encode(&h[..4]).into_bytes()
}

pub fn server_config(id: &Ident, alpn: Vec<u8>) -> Result<ServerConfig> {
    let mut cfg = ServerConfig::builder_with_provider(provider())
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_no_client_auth()
        .with_single_cert(vec![id.cert.clone()], id.key())?;
    cfg.alpn_protocols = vec![alpn];
    Ok(cfg)
}

pub fn client_config(pin: [u8; 32], alpn: Vec<u8>) -> Result<ClientConfig> {
    let p = provider();
    let mut cfg = ClientConfig::builder_with_provider(p.clone())
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(PinVerifier { pin, provider: p }))
        .with_no_client_auth();
    cfg.enable_sni = false;
    cfg.alpn_protocols = vec![alpn];
    Ok(cfg)
}

/// Any name works: no SNI goes out and the verifier checks only the pin.
pub fn any_name() -> ServerName<'static> {
    ServerName::try_from("localhost").expect("static name")
}

fn transport() -> Result<Arc<quinn::TransportConfig>> {
    let mut t = quinn::TransportConfig::default();
    t.keep_alive_interval(Some(Duration::from_secs(4)));
    t.max_idle_timeout(Some(Duration::from_secs(20).try_into().map_err(|e| anyhow!("{e}"))?));
    Ok(Arc::new(t))
}

pub fn quic_server(id: &Ident, alpn: Vec<u8>) -> Result<quinn::ServerConfig> {
    let tls = server_config(id, alpn)?;
    let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls).map_err(|e| anyhow!("quic server tls: {e}"))?;
    let mut cfg = quinn::ServerConfig::with_crypto(Arc::new(crypto));
    cfg.transport_config(transport()?);
    Ok(cfg)
}

pub fn quic_client(pin: [u8; 32], alpn: Vec<u8>) -> Result<quinn::ClientConfig> {
    let tls = client_config(pin, alpn)?;
    let crypto = quinn::crypto::rustls::QuicClientConfig::try_from(tls).map_err(|e| anyhow!("quic client tls: {e}"))?;
    let mut cfg = quinn::ClientConfig::new(Arc::new(crypto));
    cfg.transport_config(transport()?);
    Ok(cfg)
}

#[derive(Debug)]
struct PinVerifier {
    pin: [u8; 32],
    provider: Arc<CryptoProvider>,
}

impl ServerCertVerifier for PinVerifier {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        if sha256(&[end_entity.as_ref()]) == self.pin {
            Ok(ServerCertVerified::assertion())
        } else {
            Err(rustls::Error::General("certificate does not match the card".into()))
        }
    }

    fn verify_tls12_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.provider.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.provider.signature_verification_algorithms.supported_schemes()
    }
}
