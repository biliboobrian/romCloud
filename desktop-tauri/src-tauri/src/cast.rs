// Chromecast : découverte sur le réseau local (mDNS) et protocole Cast v2 (TLS + protobuf), sans
// dépendance dédiée. Le flux vidéo lui-même est servi par screencast.rs.
use crate::error::{AppError, Result};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::net::{Ipv4Addr, SocketAddrV4};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt, WriteHalf};
use tokio::sync::{mpsc, Mutex};

// ---------------------------------------------------------------------------
// Découverte (mDNS, service _googlecast._tcp)
// ---------------------------------------------------------------------------

const MDNS_ADDRESS: Ipv4Addr = Ipv4Addr::new(224, 0, 0, 251);
const MDNS_PORT: u16 = 5353;
const SERVICE: &str = "_googlecast._tcp.local";
const TYPE_A: u16 = 1;
const TYPE_PTR: u16 = 12;
const TYPE_TXT: u16 = 16;
const TYPE_SRV: u16 = 33;

/// Requête DNS (une question PTR).
fn encode_query(name: &str) -> Vec<u8> {
    let mut out = vec![0u8; 12];
    out[5] = 1; // une question
    for label in name.split('.').filter(|l| !l.is_empty()) {
        out.push(label.len() as u8);
        out.extend_from_slice(label.as_bytes());
    }
    out.extend_from_slice(&[0, 0, TYPE_PTR as u8, 0, 1]);
    out
}

/// Nom DNS à [offset], pointeurs de compression compris ; renvoie (nom, fin).
fn read_name(buf: &[u8], mut offset: usize) -> Option<(String, usize)> {
    let mut labels = Vec::new();
    let mut end: Option<usize> = None;
    let mut jumps = 0;
    loop {
        let len = *buf.get(offset)? as usize;
        if len == 0 {
            offset += 1;
            break;
        }
        if len & 0xc0 == 0xc0 {
            if end.is_none() {
                end = Some(offset + 2);
            }
            jumps += 1;
            if jumps > 32 {
                return None;
            }
            offset = ((len & 0x3f) << 8) | *buf.get(offset + 1)? as usize;
            continue;
        }
        labels.push(String::from_utf8_lossy(buf.get(offset + 1..offset + 1 + len)?).into_owned());
        offset += 1 + len;
    }
    Some((labels.join("."), end.unwrap_or(offset)))
}

#[derive(Clone)]
enum Data {
    Name(String),
    Srv(u16, String),
    A(String),
    Txt(HashMap<String, String>),
}

/// Enregistrements (réponses et additionnels) d'un paquet DNS : (nom, type, données).
fn parse_dns(buf: &[u8]) -> Vec<(String, u16, Data)> {
    let count = |i: usize| buf.get(i..i + 2).map(|b| u16::from_be_bytes([b[0], b[1]]) as usize).unwrap_or(0);
    let mut records = Vec::new();
    let mut offset = 12;
    for _ in 0..count(4) {
        let Some((_, end)) = read_name(buf, offset) else { return records };
        offset = end + 4;
    }
    let total = count(6) + count(8) + count(10);
    for _ in 0..total {
        let Some((name, end)) = read_name(buf, offset) else { break };
        if end + 10 > buf.len() {
            break;
        }
        let kind = u16::from_be_bytes([buf[end], buf[end + 1]]);
        let length = u16::from_be_bytes([buf[end + 8], buf[end + 9]]) as usize;
        let start = end + 10;
        if start + length > buf.len() {
            break;
        }
        let data = match kind {
            TYPE_PTR => read_name(buf, start).map(|(n, _)| Data::Name(n)),
            TYPE_SRV if length >= 6 => read_name(buf, start + 6).map(|(n, _)| Data::Srv(u16::from_be_bytes([buf[start + 4], buf[start + 5]]), n)),
            TYPE_A if length == 4 => Some(Data::A(format!("{}.{}.{}.{}", buf[start], buf[start + 1], buf[start + 2], buf[start + 3]))),
            TYPE_TXT => {
                let mut map = HashMap::new();
                let mut o = start;
                while o < start + length {
                    let l = buf[o] as usize;
                    let entry = String::from_utf8_lossy(&buf[o + 1..(o + 1 + l).min(start + length)]).into_owned();
                    o += 1 + l;
                    if let Some((k, v)) = entry.split_once('=') {
                        if !k.is_empty() {
                            map.insert(k.to_lowercase(), v.to_string());
                        }
                    }
                }
                Some(Data::Txt(map))
            }
            _ => None,
        };
        if let Some(data) = data {
            records.push((name.to_lowercase(), kind, data));
        }
        offset = start + length;
    }
    records
}

/// Regroupe les enregistrements en appareils Cast capables d'afficher de la vidéo.
fn collect_devices(records: &[(String, u16, Data)], sources: &HashMap<String, String>) -> Vec<Value> {
    let mut instances: Vec<String> = Vec::new();
    let mut srv: HashMap<String, (u16, String)> = HashMap::new();
    let mut txt: HashMap<String, HashMap<String, String>> = HashMap::new();
    let mut hosts: HashMap<String, String> = HashMap::new();
    let suffix = format!(".{SERVICE}");
    for (name, _, data) in records {
        match data {
            Data::Name(target) if name == SERVICE => instances.push(target.to_lowercase()),
            Data::Srv(port, target) => {
                srv.insert(name.clone(), (*port, target.clone()));
                if name.ends_with(&suffix) {
                    instances.push(name.clone());
                }
            }
            Data::Txt(map) => {
                txt.insert(name.clone(), map.clone());
            }
            Data::A(address) => {
                hosts.insert(name.clone(), address.clone());
            }
            _ => {}
        }
    }
    let mut devices: Vec<Value> = Vec::new();
    let mut seen = HashSet::new();
    let mut unique = HashSet::new();
    for instance in instances.into_iter().filter(|i| unique.insert(i.clone())) {
        let s = srv.get(&instance);
        let info = txt.get(&instance).cloned().unwrap_or_default();
        let host = s.and_then(|(_, target)| hosts.get(&target.to_lowercase()).cloned()).or_else(|| sources.get(&instance).cloned());
        let Some(host) = host else { continue };
        // Bit 0 de « ca » : sortie vidéo.
        if let Some(ca) = info.get("ca") {
            if ca.parse::<u64>().unwrap_or(0) & 1 == 0 {
                continue;
            }
        }
        if info.get("md").map(|m| m.to_lowercase().contains("cast group")).unwrap_or(false) {
            continue;
        }
        let id = info.get("id").cloned().unwrap_or_else(|| instance.clone());
        if !seen.insert(id.clone()) {
            continue;
        }
        devices.push(json!({
            "id": id,
            "name": info.get("fn").cloned().unwrap_or_else(|| instance.trim_end_matches(&suffix).to_string()),
            "model": info.get("md").cloned().unwrap_or_default(),
            "host": host,
            "port": s.map(|(p, _)| *p).unwrap_or(8009),
        }));
    }
    devices
}

fn ipv4_addresses() -> Vec<Ipv4Addr> {
    if_addrs::get_if_addrs()
        .unwrap_or_default()
        .into_iter()
        .filter(|i| !i.is_loopback())
        .filter_map(|i| match i.ip() {
            std::net::IpAddr::V4(ip) => Some(ip),
            _ => None,
        })
        .collect()
}

fn udp_socket(bind: SocketAddrV4, reuse: bool) -> std::io::Result<tokio::net::UdpSocket> {
    let socket = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::DGRAM, Some(socket2::Protocol::UDP))?;
    if reuse {
        socket.set_reuse_address(true)?;
    }
    socket.bind(&bind.into())?;
    socket.set_nonblocking(true)?;
    tokio::net::UdpSocket::from_std(socket.into())
}

/// Cherche les Chromecast du réseau local pendant 3 secondes.
pub async fn discover() -> Value {
    let query = encode_query(SERVICE);
    let (tx, mut rx) = mpsc::unbounded_channel::<(Vec<u8>, String)>();
    let mut tasks = Vec::new();
    let mut listen = |socket: tokio::net::UdpSocket| {
        let socket = Arc::new(socket);
        let tx = tx.clone();
        let reader = socket.clone();
        tasks.push(tokio::spawn(async move {
            let mut buf = vec![0u8; 9000];
            while let Ok((n, from)) = reader.recv_from(&mut buf).await {
                let _ = tx.send((buf[..n].to_vec(), from.ip().to_string()));
            }
        }));
        socket
    };
    // Port 5353 partagé : réponses envoyées au groupe multicast.
    if let Ok(socket) = udp_socket(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, MDNS_PORT), true) {
        for address in ipv4_addresses() {
            let _ = socket.join_multicast_v4(MDNS_ADDRESS, address);
        }
        listen(socket);
    }
    // Un port libre par interface : réponses directes (« legacy unicast »), requête envoyée sur chaque réseau.
    let mut senders = Vec::new();
    for address in ipv4_addresses() {
        if let Ok(socket) = udp_socket(SocketAddrV4::new(address, 0), false) {
            let _ = socket.set_multicast_loop_v4(true);
            senders.push(listen(socket));
        }
    }
    let target = SocketAddrV4::new(MDNS_ADDRESS, MDNS_PORT);
    for socket in &senders {
        let _ = socket.send_to(&query, target).await;
    }
    let resend = {
        let senders = senders.clone();
        let query = query.clone();
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_secs(1)).await;
            for socket in &senders {
                let _ = socket.send_to(&query, target).await;
            }
        })
    };
    drop(tx);
    let mut records = Vec::new();
    let mut sources: HashMap<String, String> = HashMap::new();
    let deadline = tokio::time::Instant::now() + Duration::from_secs(3);
    while let Ok(Some((packet, from))) = tokio::time::timeout_at(deadline, rx.recv()).await {
        for (name, kind, data) in parse_dns(&packet) {
            if kind == TYPE_SRV || kind == TYPE_TXT {
                sources.insert(name.clone(), from.clone());
            }
            if let (TYPE_PTR, Data::Name(target)) = (kind, &data) {
                if name == SERVICE {
                    sources.insert(target.to_lowercase(), from.clone());
                }
            }
            records.push((name, kind, data));
        }
    }
    resend.abort();
    for task in tasks {
        task.abort();
    }
    let mut devices = collect_devices(&records, &sources);
    devices.sort_by_key(|d| d["name"].as_str().unwrap_or("").to_lowercase());
    Value::Array(devices)
}

// ---------------------------------------------------------------------------
// Messages Cast v2 : protobuf CastMessage précédé de sa longueur (32 bits)
// ---------------------------------------------------------------------------

fn varint(mut n: u64, out: &mut Vec<u8>) {
    while n > 0x7f {
        out.push((n as u8 & 0x7f) | 0x80);
        n >>= 7;
    }
    out.push(n as u8);
}

/// Message texte (JSON) : protocol_version (1), source_id (2), destination_id (3), namespace (4), payload_type (5), payload_utf8 (6).
fn encode_message(source: &str, destination: &str, namespace: &str, data: &Value) -> Vec<u8> {
    let mut body = vec![0x08, 0x00];
    let mut string = |field: u64, value: &str| {
        varint((field << 3) | 2, &mut body);
        varint(value.len() as u64, &mut body);
        body.extend_from_slice(value.as_bytes());
    };
    string(2, source);
    string(3, destination);
    string(4, namespace);
    body.extend_from_slice(&[0x28, 0x00]);
    varint((6 << 3) | 2, &mut body);
    let payload = data.to_string();
    varint(payload.len() as u64, &mut body);
    body.extend_from_slice(payload.as_bytes());
    let mut out = (body.len() as u32).to_be_bytes().to_vec();
    out.extend(body);
    out
}

/// Message reçu (sans l'en-tête de longueur) : (source, espace de noms, JSON).
fn decode_message(buf: &[u8]) -> Option<(String, String, Value)> {
    let mut fields: HashMap<u64, Vec<u8>> = HashMap::new();
    let mut o = 0;
    let read_varint = |o: &mut usize| -> Option<u64> {
        let mut value = 0u64;
        let mut shift = 0;
        loop {
            let byte = *buf.get(*o)?;
            *o += 1;
            value |= ((byte & 0x7f) as u64) << shift;
            if byte & 0x80 == 0 {
                return Some(value);
            }
            shift += 7;
        }
    };
    while o < buf.len() {
        let key = read_varint(&mut o)?;
        match key & 7 {
            0 => {
                read_varint(&mut o)?;
            }
            2 => {
                let len = read_varint(&mut o)? as usize;
                fields.insert(key >> 3, buf.get(o..o + len)?.to_vec());
                o += len;
            }
            _ => return None,
        }
    }
    let text = |n: u64| fields.get(&n).map(|b| String::from_utf8_lossy(b).into_owned()).unwrap_or_default();
    let data = serde_json::from_str(&text(6)).ok()?;
    Some((text(2), text(4), data))
}

// ---------------------------------------------------------------------------
// Session : lance le lecteur multimédia par défaut du Chromecast sur une URL
// ---------------------------------------------------------------------------

const NS_CONNECTION: &str = "urn:x-cast:com.google.cast.tp.connection";
const NS_HEARTBEAT: &str = "urn:x-cast:com.google.cast.tp.heartbeat";
const NS_RECEIVER: &str = "urn:x-cast:com.google.cast.receiver";
const NS_MEDIA: &str = "urn:x-cast:com.google.cast.media";
const DEFAULT_MEDIA_RECEIVER: &str = "CC1AD845";
const SENDER: &str = "sender-romcloud";

/// Évènement de la session : lecture commencée, arrêtée (télévision, autre application), échec.
pub enum Event {
    Playing,
    Ended,
    Failed(String),
}

type Writer = WriteHalf<tokio_native_tls::TlsStream<tokio::net::TcpStream>>;

/// Connexion à un Chromecast.
pub struct CastSession {
    writer: Arc<Mutex<Writer>>,
    request_id: Arc<AtomicU64>,
    session_id: Arc<std::sync::Mutex<Option<String>>>,
    closed: Arc<AtomicBool>,
    tasks: Vec<tokio::task::JoinHandle<()>>,
}

async fn send(writer: &Mutex<Writer>, destination: &str, namespace: &str, data: Value) {
    let _ = writer.lock().await.write_all(&encode_message(SENDER, destination, namespace, &data)).await;
}

impl CastSession {
    /**
     * Ouvre la connexion TLS, lance le lecteur par défaut puis lui fait lire le média renvoyé par
     * [media_for](adresse locale) : (url, type, titre). Renvoie la session et ses évènements.
     */
    pub async fn start(device: &Value, media_for: impl FnOnce(String) -> (String, String, String)) -> Result<(CastSession, mpsc::UnboundedReceiver<Event>)> {
        let host = device["host"].as_str().unwrap_or("").to_string();
        let port = device["port"].as_u64().unwrap_or(8009) as u16;
        let tcp = tokio::time::timeout(Duration::from_secs(10), tokio::net::TcpStream::connect((host.as_str(), port)))
            .await
            .map_err(|_| AppError::msg("timeout"))??;
        let local = tcp.local_addr()?.ip().to_string();
        let connector = native_tls::TlsConnector::builder()
            .danger_accept_invalid_certs(true)
            .danger_accept_invalid_hostnames(true)
            .build()
            .map_err(|e| AppError::msg(e.to_string()))?;
        let tls = tokio_native_tls::TlsConnector::from(connector).connect(&host, tcp).await.map_err(|e| AppError::msg(e.to_string()))?;
        let (mut reader, writer) = tokio::io::split(tls);
        let writer = Arc::new(Mutex::new(writer));
        let request_id = Arc::new(AtomicU64::new(1));
        let session_id = Arc::new(std::sync::Mutex::new(None::<String>));
        let closed = Arc::new(AtomicBool::new(false));
        let (events, mut rx) = mpsc::unbounded_channel::<Event>();

        send(&writer, "receiver-0", NS_CONNECTION, json!({ "type": "CONNECT" })).await;
        let heartbeat = {
            let writer = writer.clone();
            tokio::spawn(async move {
                loop {
                    tokio::time::sleep(Duration::from_secs(5)).await;
                    send(&writer, "receiver-0", NS_HEARTBEAT, json!({ "type": "PING" })).await;
                }
            })
        };
        let (url, content_type, title) = media_for(local);
        let id = request_id.fetch_add(1, Ordering::SeqCst);
        send(&writer, "receiver-0", NS_RECEIVER, json!({ "type": "LAUNCH", "appId": DEFAULT_MEDIA_RECEIVER, "requestId": id })).await;

        let reading = {
            let writer = writer.clone();
            let request_id = request_id.clone();
            let session_id = session_id.clone();
            let closed = closed.clone();
            tokio::spawn(async move {
                let mut pending = Some((url, content_type, title));
                let mut transport: Option<String> = None;
                let finish = |event: Event| {
                    if !closed.swap(true, Ordering::SeqCst) {
                        let _ = events.send(event);
                    }
                };
                loop {
                    let mut size = [0u8; 4];
                    if reader.read_exact(&mut size).await.is_err() {
                        finish(Event::Ended);
                        return;
                    }
                    let mut buf = vec![0u8; u32::from_be_bytes(size) as usize];
                    if reader.read_exact(&mut buf).await.is_err() {
                        finish(Event::Ended);
                        return;
                    }
                    let Some((source, namespace, data)) = decode_message(&buf) else { continue };
                    let kind = data["type"].as_str().unwrap_or("");
                    if namespace == NS_HEARTBEAT && kind == "PING" {
                        send(&writer, &source, NS_HEARTBEAT, json!({ "type": "PONG" })).await;
                    } else if namespace == NS_CONNECTION && kind == "CLOSE" && Some(&source) == transport.as_ref() {
                        finish(Event::Ended);
                        return;
                    } else if namespace == NS_RECEIVER {
                        if kind == "LAUNCH_ERROR" {
                            finish(Event::Failed(data["reason"].as_str().unwrap_or("LAUNCH_ERROR").to_string()));
                            return;
                        }
                        if kind != "RECEIVER_STATUS" {
                            continue;
                        }
                        let app = data["status"]["applications"].as_array().and_then(|a| a.iter().find(|a| a["appId"] == DEFAULT_MEDIA_RECEIVER).cloned());
                        let known = session_id.lock().unwrap().clone();
                        if let (Some(_), Some(app)) = (&pending, app.as_ref().filter(|a| a["transportId"].is_string())) {
                            let (url, content_type, title) = pending.take().unwrap();
                            let t = app["transportId"].as_str().unwrap_or("").to_string();
                            *session_id.lock().unwrap() = app["sessionId"].as_str().map(String::from);
                            transport = Some(t.clone());
                            send(&writer, &t, NS_CONNECTION, json!({ "type": "CONNECT" })).await;
                            let id = request_id.fetch_add(1, Ordering::SeqCst);
                            send(
                                &writer,
                                &t,
                                NS_MEDIA,
                                json!({
                                    "type": "LOAD", "autoplay": true, "currentTime": 0, "requestId": id,
                                    "media": { "contentId": url, "contentType": content_type, "streamType": "LIVE", "metadata": { "metadataType": 0, "title": title } },
                                }),
                            )
                            .await;
                        } else if let Some(known) = known {
                            if app.as_ref().and_then(|a| a["sessionId"].as_str()) != Some(known.as_str()) {
                                finish(Event::Ended); // lecteur fermé ou remplacé par une autre application
                                return;
                            }
                        }
                    } else if namespace == NS_MEDIA {
                        if ["LOAD_FAILED", "LOAD_CANCELLED", "INVALID_REQUEST"].contains(&kind) {
                            finish(Event::Failed(data["reason"].as_str().unwrap_or(kind).to_string()));
                            return;
                        }
                        if kind != "MEDIA_STATUS" {
                            continue;
                        }
                        let status = &data["status"][0];
                        match (status["playerState"].as_str(), status["idleReason"].as_str()) {
                            (Some("PLAYING") | Some("BUFFERING"), _) => {
                                let _ = events.send(Event::Playing);
                            }
                            (Some("IDLE"), Some("ERROR")) => {
                                finish(Event::Failed("MEDIA_ERROR".into()));
                                return;
                            }
                            (Some("IDLE"), Some(_)) => {
                                finish(Event::Ended);
                                return;
                            }
                            _ => {}
                        }
                    }
                }
            })
        };
        let session = CastSession { writer, request_id, session_id, closed, tasks: vec![heartbeat, reading] };
        // Lecture commencée dans les 30 secondes, sinon échec.
        let first = tokio::time::timeout(Duration::from_secs(30), rx.recv()).await;
        match first {
            Ok(Some(Event::Playing)) => Ok((session, rx)),
            Ok(Some(Event::Failed(reason))) => {
                session.stop().await;
                Err(AppError::msg(reason))
            }
            Ok(_) => {
                session.stop().await;
                Err(AppError::msg("closed"))
            }
            Err(_) => {
                session.stop().await;
                Err(AppError::msg("timeout"))
            }
        }
    }

    /// Ferme le lecteur sur le Chromecast puis la connexion.
    pub async fn stop(&self) {
        self.closed.store(true, Ordering::SeqCst);
        let session = self.session_id.lock().unwrap().clone();
        if let Some(session) = session {
            let id = self.request_id.fetch_add(1, Ordering::SeqCst);
            send(&self.writer, "receiver-0", NS_RECEIVER, json!({ "type": "STOP", "sessionId": session, "requestId": id })).await;
        }
        tokio::time::sleep(Duration::from_millis(500)).await; // laisse partir le message STOP
        let _ = self.writer.lock().await.shutdown().await;
        for task in &self.tasks {
            task.abort();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Réponse mDNS d'un Chromecast : PTR, puis SRV, TXT et A en additionnels (noms compressés).
    fn chromecast_answer(name: &str, ca: &str, model: &str, ip: [u8; 4]) -> Vec<u8> {
        let mut out: Vec<u8> = vec![0, 0, 0x84, 0, 0, 0, 0, 1, 0, 0, 0, 3];
        let dns_name = |labels: &[&str], pointer: Option<usize>| {
            let mut b = Vec::new();
            for l in labels {
                b.push(l.len() as u8);
                b.extend_from_slice(l.as_bytes());
            }
            match pointer {
                Some(p) => b.extend_from_slice(&[0xc0 | (p >> 8) as u8, p as u8]),
                None => b.push(0),
            }
            b
        };
        let record = |out: &mut Vec<u8>, owner: &[u8], kind: u16, rdata: &[u8]| {
            out.extend_from_slice(owner);
            out.extend_from_slice(&kind.to_be_bytes());
            out.extend_from_slice(&0x8001u16.to_be_bytes());
            out.extend_from_slice(&[0, 0, 0, 120]);
            out.extend_from_slice(&(rdata.len() as u16).to_be_bytes());
            out.extend_from_slice(rdata);
        };
        let service_offset = out.len();
        let service = dns_name(&["_googlecast", "_tcp", "local"], None);
        // PTR : _googlecast._tcp.local -> Chromecast-abc._googlecast._tcp.local
        record(&mut out, &service, TYPE_PTR, &dns_name(&["Chromecast-abc"], Some(service_offset)));
        let instance_offset = out.len() - ("Chromecast-abc".len() + 3);
        let instance = [0xc0, instance_offset as u8];
        let host = dns_name(&["abc", "local"], None);
        let mut srv = vec![0, 0, 0, 0];
        srv.extend_from_slice(&8009u16.to_be_bytes());
        srv.extend_from_slice(&host);
        record(&mut out, &instance, TYPE_SRV, &srv);
        let mut txt = Vec::new();
        for entry in ["id=abc123".to_string(), format!("fn={name}"), format!("md={model}"), format!("ca={ca}")] {
            txt.push(entry.len() as u8);
            txt.extend_from_slice(entry.as_bytes());
        }
        record(&mut out, &instance, TYPE_TXT, &txt);
        record(&mut out, &host, TYPE_A, &ip);
        out
    }

    fn salon() -> Vec<u8> {
        chromecast_answer("Salon", "4101", "Chromecast", [192, 168, 1, 42])
    }

    #[test]
    fn requete_mdns_une_question_ptr() {
        let q = encode_query("_googlecast._tcp.local");
        assert_eq!(u16::from_be_bytes([q[4], q[5]]), 1);
        assert_eq!(u16::from_be_bytes([q[q.len() - 4], q[q.len() - 3]]), 12);
        assert!(q.windows(11).any(|w| w == b"_googlecast"));
    }

    #[test]
    fn reponse_mdns_appareil_complet() {
        let devices = collect_devices(&parse_dns(&salon()), &HashMap::new());
        assert_eq!(devices, vec![json!({ "id": "abc123", "name": "Salon", "model": "Chromecast", "host": "192.168.1.42", "port": 8009 })]);
    }

    #[test]
    fn enceintes_et_groupes_ignores() {
        let nest = chromecast_answer("Cuisine", "4100", "Google Nest Mini", [192, 168, 1, 43]);
        assert!(collect_devices(&parse_dns(&nest), &HashMap::new()).is_empty());
        let group = chromecast_answer("Maison", "4101", "Google Cast Group", [192, 168, 1, 44]);
        assert!(collect_devices(&parse_dns(&group), &HashMap::new()).is_empty());
    }

    #[test]
    fn adresse_de_l_expediteur_sans_enregistrement_a() {
        let records: Vec<_> = parse_dns(&salon()).into_iter().filter(|r| r.1 != TYPE_A).collect();
        let sources = HashMap::from([("chromecast-abc._googlecast._tcp.local".to_string(), "10.0.0.7".to_string())]);
        assert_eq!(collect_devices(&records, &sources)[0]["host"], "10.0.0.7");
    }

    #[test]
    fn message_cast_encode_puis_decode() {
        let data = json!({ "type": "LAUNCH", "appId": "CC1AD845", "requestId": 1 });
        let frame = encode_message("sender-0", "receiver-0", "urn:x-cast:com.google.cast.receiver", &data);
        assert_eq!(u32::from_be_bytes([frame[0], frame[1], frame[2], frame[3]]) as usize, frame.len() - 4);
        let (source, namespace, decoded) = decode_message(&frame[4..]).unwrap();
        assert_eq!(source, "sender-0");
        assert_eq!(namespace, "urn:x-cast:com.google.cast.receiver");
        assert_eq!(decoded, data);
    }

    #[test]
    fn message_cast_long() {
        let url = format!("http://192.168.1.10:5000/{}.webm", "a".repeat(300));
        let frame = encode_message("s", "d", "n", &json!({ "url": url }));
        assert_eq!(decode_message(&frame[4..]).unwrap().2["url"], url);
    }
}
