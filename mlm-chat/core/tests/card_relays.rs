//! A card always names the mailbox relays (SPEC 19.3) before anything else that fills it: they are
//! the only way between two phones on different operators. Before, a card that passed
//! `MAX_CARD_BYTES` lost its relays first, so a phone with many addresses and volunteers told its
//! contacts nothing of its relay, and messages never left (Ehsan and a tester, 25 Oct 2026).

use mlmchat_core::card::{Card, MAX_CARD_BYTES};
use mlmchat_core::identity::Identity;
use mlmchat_core::transport::gas::proto::RelayRef;
use std::net::SocketAddr;

fn relay(n: u8) -> Vec<u8> {
    // a deployment id as Google gives them: «AKfycb» and 66 more characters
    let id = format!("AKfycb{}", (0..66).map(|i| char::from(b'a' + ((i + n as usize) % 26) as u8)).collect::<String>());
    RelayRef::new(&id, [n; 16]).unwrap().to_bytes()
}

#[test]
fn a_full_card_keeps_its_relays() {
    let mut id = Identity::new("ehsan-with-a-rather-long-name").unwrap();
    id.set_relays(vec![relay(1), relay(2), relay(3)]);
    // a phone with every kind of address: LAN, global and temporary IPv6, a UPnP door
    let addrs: Vec<Vec<u8>> = [
        "192.168.1.23:40443",
        "10.0.0.7:40443",
        "[2001:db8:1234:5678:9abc:def0:1234:5678]:40443",
        "[2001:db8:1234:5678:1111:2222:3333:4444]:40443",
        "[2001:db8:aaaa:bbbb:cccc:dddd:eeee:ffff]:40443",
        "[fd00:1234:5678::1]:40443",
        "5.6.7.8:51234",
        "[2a02:4780:1:2:3:4:5:6]:40443",
    ]
    .iter()
    .map(|a| mlmchat_core::addr::encode(&a.parse::<SocketAddr>().unwrap()))
    .collect();
    // three volunteers, each as long as a card allows
    let boot: Vec<Vec<u8>> = (0..3u8).map(|i| vec![i; mlmchat_core::card::MAX_BOOT_BYTES]).collect();
    let card = id.session_card(addrs, boot, 3600).unwrap();
    let bytes = card.to_bytes();
    println!("card {} bytes: {} addresses, {} volunteers, {} relays", bytes.len(), card.addrs.len(), card.boot.len(), card.relays.len());
    assert!(bytes.len() <= MAX_CARD_BYTES, "{} bytes", bytes.len());
    let back = Card::from_bytes(&bytes).unwrap();
    assert_eq!(back.relays.len(), 3, "every relay is named: {} addresses, {} volunteers kept", back.addrs.len(), back.boot.len());
    assert!(!back.addrs.is_empty(), "some addresses stay");
}
