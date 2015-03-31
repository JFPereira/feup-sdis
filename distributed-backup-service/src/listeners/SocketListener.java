package listeners;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;

public abstract class SocketListener extends Thread {

	public MulticastSocket socket;

	protected InetAddress address;
	protected int port;

	public SocketListener(InetAddress address, int port) {
		this.address = address;
		this.port = port;
	}

	public void run() {
		openSocket();

		byte[] buf = new byte[64000];
		DatagramPacket packet = new DatagramPacket(buf, buf.length);

		boolean done = false;
		while (!done) {
			try {
				socket.receive(packet);

				handler(packet);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		closeSocket();
	}

	private void openSocket() {
		try {
			socket = new MulticastSocket(port);

			socket.setLoopbackMode(true);
			socket.setTimeToLive(1);

			socket.joinGroup(address);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	protected abstract void handler(DatagramPacket packet);

	private void closeSocket() {
		if (socket != null)
			socket.close();
	}

}