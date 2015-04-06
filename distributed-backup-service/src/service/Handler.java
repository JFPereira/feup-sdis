package service;

import initiators.BackupChunkInitiator;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.util.ArrayList;
import java.util.Arrays;

import peer.Peer;
import peer.PeerID;
import storage.FileManager;
import utils.Log;
import utils.Utils;
import chunk.Chunk;
import chunk.ChunkID;

public class Handler implements Runnable {

	private DatagramPacket packet;

	private String header;
	private String[] headerTokens;

	private byte[] body;

	public Handler(DatagramPacket packet) {
		this.packet = packet;

		header = null;
		headerTokens = null;

		body = null;
	}

	public void run() {
		if (!extractHeader())
			return;

		MessageType messageType = MessageType.valueOf(headerTokens[0]);

		switch (messageType) {

		// 3.2 Chunk backup subprotocol

		case PUTCHUNK:
			handlePUTCHUNK();
			break;

		case STORED:
			handleSTORED();
			break;

		// 3.3 Chunk restore protocol

		case GETCHUNK:
			handleGETCHUNK();
			break;

		case CHUNK:
			handleCHUNK();
			break;

		// 3.4 File deletion subprotocol

		case DELETE:
			handleDELETE();
			break;

		// 3.5 Space reclaiming subprotocol

		case REMOVED:
			handleREMOVED();
			break;

		default:
			break;
		}
	}

	private void handlePUTCHUNK() {
		extractBody();

		ChunkID chunkID = new ChunkID(headerTokens[HeaderField.FILE_ID],
				Integer.parseInt(headerTokens[HeaderField.CHUNK_NO]));

		int replicationDeg = Integer
				.parseInt(headerTokens[HeaderField.REPLICATION_DEG]);

		try {
			if (FileManager.fileExists(chunkID.toString()))
				Peer.getCommandForwarder().sendSTORED(chunkID);
			else {
				Peer.getMcListener().startSavingStoredConfirmsFor(chunkID);

				// random delay between 0 and 400ms
				Thread.sleep(Utils.random.nextInt(400));

				if (Peer.getMcListener().getNumStoredConfirmsFor(chunkID) < replicationDeg) {
					FileManager.saveChunk(chunkID, replicationDeg, body);

					Peer.getCommandForwarder().sendSTORED(chunkID);
				}

				Peer.getMcListener().stopSavingStoredConfirmsFor(chunkID);
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void handleSTORED() {
		ChunkID chunkID = new ChunkID(headerTokens[HeaderField.FILE_ID],
				Integer.parseInt(headerTokens[HeaderField.CHUNK_NO]));

		PeerID senderID = new PeerID(packet.getAddress(), packet.getPort());

		/*
		 * If this peer is backing up this chunk, save the other peers which are
		 * also backing it up.
		 */
		Peer.getDatabase().addChunkMirror(chunkID, senderID);

		/*
		 * If this peer requested the backup, update the number of received
		 * STOREDs (replication degree).
		 */
		Peer.getMcListener().processStoredConfirm(chunkID, senderID);
	}

	private void handleGETCHUNK() {
		ChunkID chunkID = new ChunkID(headerTokens[HeaderField.FILE_ID],
				Integer.parseInt(headerTokens[HeaderField.CHUNK_NO]));

		if (Peer.getDatabase().hasChunk(chunkID)) {
			Peer.getMdrListener().startSavingCHUNKsFor(chunkID);

			try {
				Thread.sleep(Utils.random.nextInt(400));
			} catch (InterruptedException e) {
				e.printStackTrace();
				return;
			}

			boolean chunkAlreadySent = Peer.getMdrListener()
					.stopSavingCHUNKsFor(chunkID);

			if (!chunkAlreadySent) {
				Log.info("No peer has sent the chunk yet. Preparing to send chunk.");

				try {
					byte[] data = FileManager.loadChunkData(chunkID);

					Chunk chunk = new Chunk(chunkID.getFileID(),
							chunkID.getChunkNo(), -1, data);

					Peer.getCommandForwarder().sendCHUNK(chunk);
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private void handleCHUNK() {
		ChunkID chunkID = new ChunkID(headerTokens[HeaderField.FILE_ID],
				Integer.parseInt(headerTokens[HeaderField.CHUNK_NO]));

		// if we asked for the chunk
		if (Peer.getMdrListener().feedingChunksOfFile(chunkID.getFileID())) {
			extractBody();

			Chunk chunk = new Chunk(chunkID.getFileID(), chunkID.getChunkNo(),
					-1, body);

			Peer.getMdrListener().feedChunk(chunk);
		} else
			Peer.getMdrListener().registerCHUNK(chunkID);
	}

	private void handleDELETE() {
		String fileID = headerTokens[HeaderField.FILE_ID];

		ArrayList<ChunkID> chunksToBeDeleted = Peer.getDatabase()
				.getChunkIDsOfFile(fileID);

		while (!chunksToBeDeleted.isEmpty()) {
			ChunkID chunkID = chunksToBeDeleted.remove(0);

			FileManager.deleteChunk(chunkID);
		}
	}

	private void handleREMOVED() {
		ChunkID chunkID = new ChunkID(headerTokens[HeaderField.FILE_ID],
				Integer.parseInt(headerTokens[HeaderField.CHUNK_NO]));

		if (Peer.getDatabase().hasChunk(chunkID)) {
			Peer.getDatabase().removeChunkMirror(chunkID, Peer.getId());

			int currentRepDeg = Peer.getDatabase().getChunkMirrorsSize(chunkID);
			int desiredRepDeg = Peer.getDatabase().getChunkReplicationDegree(
					chunkID);

			if (currentRepDeg < desiredRepDeg) {
				Peer.getMdbListener().startSavingPUTCHUNKsFor(chunkID);

				try {
					Thread.sleep(Utils.random.nextInt(400));
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				int numPUTCHUNKsRegisteredMeanwhile = Peer.getMdbListener()
						.getNumPUTCHUNKsFor(chunkID);

				Peer.getMdbListener().stopSavingPUTCHUNKsFor(chunkID);

				if (numPUTCHUNKsRegisteredMeanwhile == 0) {
					try {
						byte[] data = FileManager.loadChunkData(chunkID);

						Chunk chunk = new Chunk(chunkID.getFileID(),
								chunkID.getChunkNo(), desiredRepDeg, data);

						new Thread(new BackupChunkInitiator(chunk)).start();
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}

	private boolean extractHeader() {
		ByteArrayInputStream stream = new ByteArrayInputStream(packet.getData());
		BufferedReader reader = new BufferedReader(
				new InputStreamReader(stream));

		try {
			header = reader.readLine();
			System.out.println(header);
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}

		headerTokens = header.split("[ ]+");

		return true;
	}

	private void extractBody() {
		int bodyStartIndex = header.getBytes().length + 2
				* Protocol.CRLF.getBytes().length;

		body = Arrays.copyOfRange(packet.getData(), bodyStartIndex,
				packet.getLength());
	}

}
