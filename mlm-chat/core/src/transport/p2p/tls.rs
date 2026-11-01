//! TLS 1.3 under every p2p connection (SPEC 9, 15.3; T18): a libp2p transport that wraps TCP in
//! real TLS 1.3 (rustls with aws-lc-rs), preferring the hybrid post-quantum key exchange
//! `X25519MLKEM768` (X25519 as the fallback). Addresses carry `/tls`: `/ip4/.../tcp/443/tls`.
//!
//! Why: libp2p's own negotiation sends `/multistream/1.0.0` and `/noise` in the clear, which
//! is both readable bytes on the wire (T18) and an easy fingerprint for a filter. Inside TLS an
//! observer sees an ordinary TLS 1.3 handshake to an IP address (no SNI) and then ciphertext; a
//! recording kept until a quantum computer exists cannot open this layer either.
//!
//! Authentication is NOT this layer's job: any certificate is accepted (it is a fresh
//! self-signed one per run, encrypted inside TLS 1.3 anyway). The libp2p Noise handshake inside
//! authenticates the peer id, and inside that the SPEC 14.7 session authenticates the user
//! (`sk`) and the locator. A party in the middle who terminates this TLS still has to break both.
//!
//! The ClientHello offers ALPN `h2`, `http/1.1` as a browser does, and the server picks `h2`: a
//! hello with no ALPN at all to port 443 stands out. What follows is not HTTP/2, but after the
//! hello everything is encrypted, the server's choice included. A peer that offers no ALPN (an
//! older core) still connects.
//!
//! The transport also refuses, both ways, any address the node's scope does not allow (SPEC
//! 15.10): this is the one place every p2p connection passes, whatever asked for it.

use futures::future::BoxFuture;
use futures::{AsyncRead, AsyncWrite, FutureExt};
use super::ledger::{Counted, Traffic};
use futures_rustls::{TlsAcceptor, TlsConnector, TlsStream};
use libp2p::core::transport::{DialOpts, ListenerId, TransportError, TransportEvent};
use libp2p::core::{Endpoint, Transport};
use libp2p::multiaddr::Protocol;
use libp2p::Multiaddr;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{aws_lc_rs, CryptoProvider};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, ServerConfig, SignatureScheme};
use std::net::IpAddr;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

/// The key exchange groups, best first.
fn provider() -> Arc<CryptoProvider> {
    let mut p = aws_lc_rs::default_provider();
    p.kx_groups = vec![aws_lc_rs::kx_group::X25519MLKEM768, aws_lc_rs::kx_group::X25519];
    Arc::new(p)
}

/// ALPN, as a browser offers it; the server prefers the first.
pub const ALPN: [&[u8]; 2] = [b"h2", b"http/1.1"];

/// Client and server configurations, TLS 1.3 only. The server certificate is made here.
pub fn configs() -> Result<(Arc<ClientConfig>, Arc<ServerConfig>), String> {
    let p = provider();
    let mut client = ClientConfig::builder_with_provider(p.clone())
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| e.to_string())?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(AnyCert(p.clone())))
        .with_no_client_auth();
    client.alpn_protocols = ALPN.iter().map(|a| a.to_vec()).collect();
    let made = rcgen::generate_simple_self_signed(vec!["localhost".to_string()]).map_err(|e| e.to_string())?;
    let cert = CertificateDer::from(made.cert.der().to_vec());
    let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(made.key_pair.serialize_der()));
    let mut server = ServerConfig::builder_with_provider(p)
        .with_protocol_versions(&[&rustls::version::TLS13])
        .map_err(|e| e.to_string())?
        .with_no_client_auth()
        .with_single_cert(vec![cert], key)
        .map_err(|e| e.to_string())?;
    server.alpn_protocols = ALPN.iter().map(|a| a.to_vec()).collect();
    Ok((Arc::new(client), Arc::new(server)))
}

/// Accepts any server certificate, but still checks the handshake signature with it.
#[derive(Debug)]
struct AnyCert(Arc<CryptoProvider>);

impl ServerCertVerifier for AnyCert {
    fn verify_server_cert(&self, _: &CertificateDer<'_>, _: &[CertificateDer<'_>], _: &ServerName<'_>, _: &[u8], _: UnixTime) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls12_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn verify_tls13_signature(&self, message: &[u8], cert: &CertificateDer<'_>, dss: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        rustls::crypto::verify_tls13_signature(message, cert, dss, &self.0.signature_verification_algorithms)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

#[derive(Debug, thiserror::Error)]
pub enum TlsError<E: std::error::Error + 'static> {
    #[error(transparent)]
    Inner(E),
    #[error("tls: {0}")]
    Tls(std::io::Error),
}

/// `/ip4/a/tcp/p/tls[/p2p/x]` -> `/ip4/a/tcp/p[/p2p/x]`; `None` without `/tls` right after `/tcp`.
pub fn strip_tls(addr: &Multiaddr) -> Option<Multiaddr> {
    let parts: Vec<Protocol<'_>> = addr.iter().collect();
    let at = parts.iter().position(|p| matches!(p, Protocol::Tls))?;
    if at == 0 || !matches!(parts[at - 1], Protocol::Tcp(_)) || parts.iter().any(|p| matches!(p, Protocol::P2pCircuit)) {
        return None;
    }
    Some(parts.into_iter().enumerate().filter(|(i, _)| *i != at).map(|(_, p)| p).collect())
}

/// Put `/tls` right after `/tcp/<port>`.
pub fn add_tls(addr: Multiaddr) -> Multiaddr {
    let mut out = Multiaddr::empty();
    for p in addr.iter() {
        let tcp = matches!(p, Protocol::Tcp(_));
        out.push(p);
        if tcp {
            out.push(Protocol::Tls);
        }
    }
    out
}

fn ip_of(a: &Multiaddr) -> Option<IpAddr> {
    a.iter().find_map(|p| match p {
        Protocol::Ip4(ip) => Some(IpAddr::V4(ip)),
        Protocol::Ip6(ip) => Some(IpAddr::V6(ip)),
        _ => None,
    })
}

/// Whether the node may connect to an IP, or accept a connection from it (SPEC 15.10).
pub type Allow = Arc<dyn Fn(IpAddr) -> bool + Send + Sync>;

pub struct TlsTransport<T> {
    inner: T,
    connector: TlsConnector,
    acceptor: TlsAcceptor,
    allow: Allow,
    /// Bytes on the wire, for a volunteer's ledger (SPEC 15.12).
    traffic: Arc<Traffic>,
}

impl<T> TlsTransport<T> {
    pub fn new(inner: T, client: Arc<ClientConfig>, server: Arc<ServerConfig>, allow: Allow, traffic: Arc<Traffic>) -> Self {
        TlsTransport { inner, connector: TlsConnector::from(client), acceptor: TlsAcceptor::from(server), allow, traffic }
    }
}

type Fut<O, E> = BoxFuture<'static, Result<TlsStream<Counted<O>>, TlsError<E>>>;

async fn client<O: AsyncRead + AsyncWrite + Unpin, E: std::error::Error + 'static>(c: TlsConnector, ip: Option<IpAddr>, s: O) -> Result<TlsStream<O>, TlsError<E>> {
    // an IP address as the server name: rustls sends no SNI then
    let name = ServerName::IpAddress(ip.unwrap_or(IpAddr::from([127, 0, 0, 1])).into());
    let t = c.connect(name, s).await.map_err(TlsError::Tls)?;
    log::debug!("tls: out, alpn {}", alpn_name(t.get_ref().1.alpn_protocol()));
    Ok(TlsStream::Client(t))
}

async fn server<O: AsyncRead + AsyncWrite + Unpin, E: std::error::Error + 'static>(a: TlsAcceptor, s: O) -> Result<TlsStream<O>, TlsError<E>> {
    let t = a.accept(s).await.map_err(TlsError::Tls)?;
    log::debug!("tls: in, alpn {}", alpn_name(t.get_ref().1.alpn_protocol()));
    Ok(TlsStream::Server(t))
}

fn alpn_name(p: Option<&[u8]>) -> &'static str {
    match p {
        Some(b"h2") => "h2",
        Some(b"http/1.1") => "http/1.1",
        Some(_) => "other",
        None => "none",
    }
}

fn refused<E: std::error::Error + 'static>() -> TlsError<E> {
    TlsError::Tls(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "outside the scope"))
}

impl<T> Transport for TlsTransport<T>
where
    T: Transport + Unpin,
    T::Output: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    T::Error: Send + Sync + 'static,
    T::Dial: Send + 'static,
    T::ListenerUpgrade: Send + 'static,
{
    type Output = TlsStream<Counted<T::Output>>;
    type Error = TlsError<T::Error>;
    type ListenerUpgrade = Fut<T::Output, T::Error>;
    type Dial = Fut<T::Output, T::Error>;

    fn listen_on(&mut self, id: ListenerId, addr: Multiaddr) -> Result<(), TransportError<Self::Error>> {
        let inner = strip_tls(&addr).ok_or(TransportError::MultiaddrNotSupported(addr))?;
        self.inner.listen_on(id, inner).map_err(|e| e.map(TlsError::Inner))
    }

    fn remove_listener(&mut self, id: ListenerId) -> bool {
        self.inner.remove_listener(id)
    }

    fn dial(&mut self, addr: Multiaddr, opts: DialOpts) -> Result<Self::Dial, TransportError<Self::Error>> {
        let inner = strip_tls(&addr).ok_or_else(|| TransportError::MultiaddrNotSupported(addr.clone()))?;
        let ip = ip_of(&addr);
        if ip.is_some_and(|ip| !(self.allow)(ip)) {
            return Err(TransportError::Other(refused()));
        }
        let dial = self.inner.dial(inner, opts).map_err(|e| e.map(TlsError::Inner))?;
        let (connector, acceptor, traffic) = (self.connector.clone(), self.acceptor.clone(), self.traffic.clone());
        Ok(async move {
            let s = Counted::new(dial.await.map_err(TlsError::Inner)?, None, traffic);
            // in a hole punch (DCUtR) the side told to be the listener is the TLS server
            match opts.role {
                Endpoint::Dialer => client(connector, ip, s).await,
                Endpoint::Listener => server(acceptor, s).await,
            }
        }
        .boxed())
    }

    fn poll(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<TransportEvent<Self::ListenerUpgrade, Self::Error>> {
        let this = self.get_mut();
        let ev = match Pin::new(&mut this.inner).poll(cx) {
            Poll::Ready(ev) => ev,
            Poll::Pending => return Poll::Pending,
        };
        Poll::Ready(match ev {
            TransportEvent::NewAddress { listener_id, listen_addr } => TransportEvent::NewAddress { listener_id, listen_addr: add_tls(listen_addr) },
            TransportEvent::AddressExpired { listener_id, listen_addr } => TransportEvent::AddressExpired { listener_id, listen_addr: add_tls(listen_addr) },
            TransportEvent::Incoming { listener_id, upgrade, local_addr, send_back_addr } => {
                let acceptor = this.acceptor.clone();
                let allowed = ip_of(&send_back_addr).is_none_or(|ip| (this.allow)(ip));
                let send_back_addr = add_tls(send_back_addr);
                // counted per connection: the swarm ties it to the peer by this address
                let conn = allowed.then(|| this.traffic.inbound(send_back_addr.to_string()));
                let traffic = this.traffic.clone();
                let upgrade = async move {
                    if !allowed {
                        return Err(refused());
                    }
                    let s = Counted::new(upgrade.await.map_err(TlsError::Inner)?, conn, traffic);
                    server(acceptor, s).await
                }
                .boxed();
                TransportEvent::Incoming { listener_id, upgrade, local_addr: add_tls(local_addr), send_back_addr }
            }
            TransportEvent::ListenerClosed { listener_id, reason } => TransportEvent::ListenerClosed { listener_id, reason: reason.map_err(TlsError::Inner) },
            TransportEvent::ListenerError { listener_id, error } => TransportEvent::ListenerError { listener_id, error: TlsError::Inner(error) },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tls_addresses() {
        let a: Multiaddr = "/ip4/203.0.113.7/tcp/443/tls".parse().unwrap();
        assert_eq!(strip_tls(&a).unwrap().to_string(), "/ip4/203.0.113.7/tcp/443");
        assert_eq!(add_tls(strip_tls(&a).unwrap()), a);
        assert!(strip_tls(&"/ip4/203.0.113.7/tcp/443".parse().unwrap()).is_none());
        let p: Multiaddr = "/ip6/2001:db8::1/tcp/40443/tls/p2p/12D3KooWDpJ7As7BWAwRMfu1VU2WCqNjvq387JEYKDBj4kx6nXTN".parse().unwrap();
        assert_eq!(add_tls(strip_tls(&p).unwrap()), p);
        assert!(configs().is_ok());
    }

    /// One real handshake in memory: TLS 1.3, the hybrid group and ALPN h2 are what is negotiated.
    #[test]
    fn negotiates_tls13_with_the_hybrid_group() {
        let (c, s) = configs().unwrap();
        let (client, srv) = handshake(c, s);
        assert_eq!(client.protocol_version(), Some(rustls::ProtocolVersion::TLSv1_3));
        assert_eq!(client.negotiated_key_exchange_group().map(|g| g.name()), Some(rustls::NamedGroup::X25519MLKEM768));
        assert_eq!(client.alpn_protocol(), Some(&b"h2"[..]));
        assert_eq!(srv.alpn_protocol(), Some(&b"h2"[..]));
    }

    /// The scope is checked before any packet leaves (SPEC 15.10).
    #[test]
    fn refuses_what_the_scope_does_not_allow() {
        let (c, s) = configs().unwrap();
        let tcp = libp2p::tcp::tokio::Transport::new(libp2p::tcp::Config::default());
        let mut t = TlsTransport::new(tcp, c, s, Arc::new(|ip: IpAddr| ip != IpAddr::from([203, 0, 113, 7])), Arc::new(Traffic::default()));
        let opts = DialOpts { role: Endpoint::Dialer, port_use: libp2p::core::transport::PortUse::New };
        assert!(matches!(t.dial("/ip4/203.0.113.7/tcp/443/tls".parse().unwrap(), opts), Err(TransportError::Other(TlsError::Tls(_)))));
    }

    /// An older core offers no ALPN and its server picks none: both still connect to a new one.
    #[test]
    fn a_peer_without_alpn_still_connects() {
        let (c, s) = configs().unwrap();
        let mut old_client = (*c).clone();
        old_client.alpn_protocols.clear();
        let (client, _) = handshake(Arc::new(old_client), s.clone());
        assert_eq!(client.alpn_protocol(), None);
        let mut old_server = (*s).clone();
        old_server.alpn_protocols.clear();
        let (client, _) = handshake(c, Arc::new(old_server));
        assert_eq!(client.alpn_protocol(), None);
    }

    fn handshake(c: Arc<ClientConfig>, s: Arc<ServerConfig>) -> (rustls::ClientConnection, rustls::ServerConnection) {
        let mut client = rustls::ClientConnection::new(c, ServerName::IpAddress(IpAddr::from([203, 0, 113, 7]).into())).unwrap();
        let mut srv = rustls::ServerConnection::new(s).unwrap();
        let mut buf = Vec::new();
        for _ in 0..10 {
            buf.clear();
            while client.wants_write() {
                client.write_tls(&mut buf).unwrap();
            }
            let mut r = &buf[..];
            while !r.is_empty() {
                srv.read_tls(&mut r).unwrap();
            }
            srv.process_new_packets().unwrap();
            buf.clear();
            while srv.wants_write() {
                srv.write_tls(&mut buf).unwrap();
            }
            let mut r = &buf[..];
            while !r.is_empty() {
                client.read_tls(&mut r).unwrap();
            }
            client.process_new_packets().unwrap();
            if !client.is_handshaking() && !srv.is_handshaking() {
                break;
            }
        }
        assert!(!client.is_handshaking() && !srv.is_handshaking());
        (client, srv)
    }
}
