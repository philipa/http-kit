package me.shenfeng.http.client;

import static java.lang.Character.isWhitespace;
import static me.shenfeng.http.HttpUtils.BUFFER_SIZE;
import static me.shenfeng.http.HttpUtils.CHUNKED;
import static me.shenfeng.http.HttpUtils.CONTENT_LENGTH;
import static me.shenfeng.http.HttpUtils.CR;
import static me.shenfeng.http.HttpUtils.LF;
import static me.shenfeng.http.HttpUtils.MAX_LINE;
import static me.shenfeng.http.HttpUtils.TRANSFER_ENCODING;
import static me.shenfeng.http.HttpUtils.findEndOfString;
import static me.shenfeng.http.HttpUtils.findNonWhitespace;
import static me.shenfeng.http.HttpUtils.findWhitespace;
import static me.shenfeng.http.HttpUtils.getChunkSize;
import static me.shenfeng.http.client.IEventListener.ABORT;
import static me.shenfeng.http.codec.HttpVersion.HTTP_1_0;
import static me.shenfeng.http.codec.HttpVersion.HTTP_1_1;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

import me.shenfeng.http.codec.HttpStatus;
import me.shenfeng.http.codec.HttpVersion;
import me.shenfeng.http.codec.LineTooLargeException;
import me.shenfeng.http.codec.ProtocolException;

public class HttpClientDecoder {


	private Map<String, String> headers = new TreeMap<String, String>();

	// package visible
	IEventListener listener;
	// single threaded, shared ok
	private static byte[] content = new byte[BUFFER_SIZE];
	byte[] lineBuffer = new byte[MAX_LINE];
	int lineBufferCnt = 0;
	int readRemaining = 0;
	ClientDecoderState state = ClientDecoderState.READ_INITIAL;

	public HttpClientDecoder(IEventListener listener) {
		this.listener = listener;
	}

	private void complete() {
		state = ClientDecoderState.ALL_READ;
		listener.onCompleted();
	}

	private void parseInitialLine(String sb) {
		int aStart;
		int aEnd;
		int bStart;
		int bEnd;
		int cStart;
		int cEnd;

		aStart = findNonWhitespace(sb, 0);
		aEnd = findWhitespace(sb, aStart);

		bStart = findNonWhitespace(sb, aEnd);
		bEnd = findWhitespace(sb, bStart);

		cStart = findNonWhitespace(sb, bEnd);
		cEnd = findEndOfString(sb);

		if (cStart < cEnd) {
			int status = Integer.parseInt(sb.substring(bStart, bEnd));
			HttpStatus s = HttpStatus.valueOf(status);

			HttpVersion version = HTTP_1_1;
			if ("HTTP/1.0".equals(sb.substring(aStart, cEnd))) {
				version = HTTP_1_0;
			}

			if (listener.onInitialLineReceived(version, s) != ABORT) {
				state = ClientDecoderState.READ_HEADER;
			} else {
				state = ClientDecoderState.ABORTED;
			}

		} else {
			listener.onThrowable(new ProtocolException());
		}
	}

	public ClientDecoderState decode(ByteBuffer buffer) throws LineTooLargeException {
		String line;
		int toRead;
		while (buffer.hasRemaining() && state != ClientDecoderState.ALL_READ) {
			switch (state) {
			case READ_INITIAL:
				line = readLine(buffer);
				if (line != null) {
					parseInitialLine(line);
				}
				break;
			case READ_HEADER:
				readHeaders(buffer);
				break;
			case READ_CHUNK_SIZE:
				line = readLine(buffer);
				if (line != null) {
					readRemaining = getChunkSize(line);
					if (readRemaining == 0) {
						state = ClientDecoderState.READ_CHUNK_FOOTER;
					} else {
						state = ClientDecoderState.READ_CHUNKED_CONTENT;
					}
				}
				break;
			case READ_FIXED_LENGTH_CONTENT:
				toRead = Math.min(buffer.remaining(), readRemaining);
				buffer.get(content, 0, toRead);
				if (listener.onBodyReceived(content, toRead) == ABORT) {
					state = ClientDecoderState.ABORTED;
				} else {
					readRemaining -= toRead;
					if (readRemaining == 0) {
						complete();
					}
				}
				break;
			case READ_CHUNKED_CONTENT:
				toRead = Math.min(buffer.remaining(), readRemaining);
				buffer.get(content, 0, toRead);
				if (listener.onBodyReceived(content, toRead) == ABORT) {
					state = ClientDecoderState.ABORTED;
				} else {
					readRemaining -= toRead;
					if (readRemaining == 0) {
						state = ClientDecoderState.READ_CHUNK_DELIMITER;
					}
				}
				break;
			case READ_CHUNK_FOOTER:
				readEmptyLine(buffer);
				complete();
				break;
			case READ_CHUNK_DELIMITER:
				readEmptyLine(buffer);
				state = ClientDecoderState.READ_CHUNK_SIZE;
				break;
			}
		}
		return state;
	}

	public IEventListener getListener() {
		return listener;
	}

	void readEmptyLine(ByteBuffer buffer) {
		byte b = buffer.get();
		if (b == CR) {
			buffer.get(); // should be LF
		} else if (b == LF) {
		}
	}

	private void readHeaders(ByteBuffer buffer) throws LineTooLargeException {
		String line = readLine(buffer);
		while (line != null && !line.isEmpty()) {
			splitAndAddHeader(line);
			line = readLine(buffer);
		}
		if (listener.onHeadersReceived(headers) != ABORT) {
			String te = headers.get(TRANSFER_ENCODING);
			if (CHUNKED.equals(te)) {
				state = ClientDecoderState.READ_CHUNK_SIZE;
			} else {
				String cl = headers.get(CONTENT_LENGTH);
				if (cl != null) {
					readRemaining = Integer.parseInt(cl);
					if (readRemaining == 0) {
						complete();
					} else {
						state = ClientDecoderState.READ_FIXED_LENGTH_CONTENT;
					}
				} else {
					state = ClientDecoderState.READ_VARIABLE_LENGTH_CONTENT;
				}
			}
		} else {
			state = ClientDecoderState.ABORTED;
		}
	};

	String readLine(ByteBuffer buffer) throws LineTooLargeException {
		byte b;
		boolean more = true;
		while (buffer.hasRemaining() && more) {
			b = buffer.get();
			if (b == CR) {
				if (buffer.get() == LF)
					more = false;
			} else if (b == LF) {
				more = false;
			} else {
				lineBuffer[lineBufferCnt] = b;
				++lineBufferCnt;
				if (lineBufferCnt >= MAX_LINE) {
					throw new LineTooLargeException();
				}
			}
		}
		String line = null;
		if (!more) {
			line = new String(lineBuffer, 0, lineBufferCnt);
			lineBufferCnt = 0;
		}
		return line;
	}

	public void reset() {
		headers.clear();
		state = ClientDecoderState.READ_INITIAL;
	}

	void splitAndAddHeader(String line) {
		final int length = line.length();
		int nameStart;
		int nameEnd;
		int colonEnd;
		int valueStart;
		int valueEnd;

		nameStart = findNonWhitespace(line, 0);
		for (nameEnd = nameStart; nameEnd < length; nameEnd++) {
			char ch = line.charAt(nameEnd);
			if (ch == ':' || isWhitespace(ch)) {
				break;
			}
		}

		for (colonEnd = nameEnd; colonEnd < length; colonEnd++) {
			if (line.charAt(colonEnd) == ':') {
				colonEnd++;
				break;
			}
		}

		valueStart = findNonWhitespace(line, colonEnd);
		valueEnd = findEndOfString(line);

		String key = line.substring(nameStart, nameEnd);
		String value = line.substring(valueStart, valueEnd);
		headers.put(key, value);
	}
}
