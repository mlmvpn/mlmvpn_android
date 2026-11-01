//! A libp2p stream as a blocking pipe, so the SPEC 14.7 session (`session.rs`) runs on it
//! unchanged. Only std threads use it, never a task of the runtime.

use futures::{AsyncReadExt, AsyncWriteExt};
use libp2p::Stream;
use std::io::{Error, ErrorKind, Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::Handle;

pub struct BlockingStream {
    stream: Stream,
    rt: Handle,
    timeout: Duration,
    stopped: Arc<AtomicBool>,
}

impl BlockingStream {
    pub fn new(stream: Stream, rt: Handle, timeout: Duration, stopped: Arc<AtomicBool>) -> BlockingStream {
        BlockingStream { stream, rt, timeout, stopped }
    }

    fn check(&self) -> std::io::Result<()> {
        if self.stopped.load(Ordering::Relaxed) {
            return Err(Error::new(ErrorKind::BrokenPipe, "transport stopped"));
        }
        Ok(())
    }
}

impl Read for BlockingStream {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        self.check()?;
        let t = self.timeout;
        let stream = &mut self.stream;
        match self.rt.block_on(async move { tokio::time::timeout(t, stream.read(buf)).await }) {
            Ok(r) => r,
            Err(_) => Err(Error::new(ErrorKind::TimedOut, "read timed out")),
        }
    }
}

impl Write for BlockingStream {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.check()?;
        let t = self.timeout;
        let stream = &mut self.stream;
        match self.rt.block_on(async move { tokio::time::timeout(t, stream.write(buf)).await }) {
            Ok(r) => r,
            Err(_) => Err(Error::new(ErrorKind::TimedOut, "write timed out")),
        }
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.check()?;
        let t = self.timeout;
        let stream = &mut self.stream;
        match self.rt.block_on(async move { tokio::time::timeout(t, stream.flush()).await }) {
            Ok(r) => r,
            Err(_) => Err(Error::new(ErrorKind::TimedOut, "flush timed out")),
        }
    }
}

impl Drop for BlockingStream {
    fn drop(&mut self) {
        if self.stopped.load(Ordering::Relaxed) || Handle::try_current().is_ok() {
            return;
        }
        let stream = &mut self.stream;
        let _ = self.rt.block_on(async move { tokio::time::timeout(Duration::from_secs(2), stream.close()).await });
    }
}
