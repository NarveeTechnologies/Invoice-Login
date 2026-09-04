package com.invoice.mail;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An SMTP port that accepts a TCP connection and then says nothing.
 *
 * <p>This is the failure that matters, and it is not the one people test for. A
 * refused connection fails fast and is obvious. A relay that completes the TCP
 * handshake and then stalls — a firewall blackholing traffic, an overloaded
 * provider, a half-open NAT mapping — leaves the client waiting for a banner
 * that never comes, for as long as the read timeout allows. Without a read
 * timeout, forever.
 *
 * <p>Never emits a {@code 220} greeting, so JavaMail blocks in its initial read.
 */
public final class StalledSmtpServer implements AutoCloseable {

	private final ServerSocket serverSocket;
	private final Thread acceptor;
	private final AtomicBoolean running = new AtomicBoolean(true);
	private final List<Socket> accepted = Collections.synchronizedList(new ArrayList<>());

	public StalledSmtpServer() throws IOException {
		this.serverSocket = new ServerSocket(0);
		this.acceptor = new Thread(this::acceptForever, "stalled-smtp");
		this.acceptor.setDaemon(true);
		this.acceptor.start();
	}

	private void acceptForever() {
		while (running.get()) {
			try {
				Socket socket = serverSocket.accept();
				// Held open and ignored. No banner, no close: the client must
				// be the one to give up, which is the whole point.
				accepted.add(socket);
			} catch (IOException e) {
				return;
			}
		}
	}

	public int port() {
		return serverSocket.getLocalPort();
	}

	public int connectionsAccepted() {
		return accepted.size();
	}

	@Override
	public void close() {
		running.set(false);
		synchronized (accepted) {
			for (Socket socket : accepted) {
				try {
					socket.close();
				} catch (IOException ignored) {
					// closing on the way out
				}
			}
		}
		try {
			serverSocket.close();
		} catch (IOException ignored) {
			// closing on the way out
		}
		acceptor.interrupt();
	}
}
