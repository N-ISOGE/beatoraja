package bms.player.beatoraja.audio;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Logger;

import org.jflac.FLACDecoder;
import org.jflac.metadata.StreamInfo;

import com.badlogic.gdx.backends.lwjgl3.audio.OggInputStream;
import com.badlogic.gdx.utils.BufferUtils;
import com.badlogic.gdx.utils.StreamUtils;
import com.badlogic.gdx.utils.StreamUtils.OptimizedByteArrayOutputStream;

import bms.player.beatoraja.song.SongResource;
import bms.player.beatoraja.song.SongResources;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.MP3Decoder;
import javazoom.jl.decoder.OutputBuffer;

/**
 * PCM音源処理用クラス
 * 
 * @author exch
 */
public abstract class PCM<T> {

	protected static final boolean USE_UNSAFE = false;

	/**
	 * チャンネル数
	 */
	public final int channels;
	/**
	 * 音源のサンプリングレート(Hz)
	 */
	public final int sampleRate;
	/**
	 * PCMデータ
	 */
	public final T sample;
	/**
	 * PCMデータ開始位置
	 */
	public final int start;
	/**
	 * PCMデータ長
	 */	
	public final int len;

	PCM(int channels, int sampleRate, int start, int len, T sample) {
		this.channels = channels;
		this.sampleRate = sampleRate;
		this.start = start;
		this.len = len;
		this.sample = sample;
	}

	public static PCM load(Path p, AudioDriver driver) {
		return load(SongResources.fromPath(p), driver);
	}

	/** Loads PCM directly from a song resource without archive-wide extraction. */
	public static PCM load(SongResource resource, AudioDriver driver) {
		try {
			PCMLoader loader = new PCMLoader(driver);
			loader.loadPCM(resource);
			
			PCM pcm = null;
			if(loader.bitsPerSample > 16) {
//				System.out.println("FLOAT");
				pcm = FloatPCM.loadPCM(loader);				
			} else if(loader.bitsPerSample == 16) {
				if(loader.pcm.isDirect()) {
					pcm = ShortDirectPCM.loadPCM(loader);
				} else {
					pcm = ShortPCM.loadPCM(loader);					
				}
			} else {
				pcm = BytePCM.loadPCM(loader);
			}
			
			if(pcm.validate()) {
				return pcm;
			} else {
				Logger.getGlobal().warning("音源の読み込みに失敗しました - file : " + resource.displayPath());
				return null;
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		
		return null;
	}
	
	public static PCM load(String name, AudioDriver driver) {
		for(Path path : AudioDriver.getPaths(name)) {
			PCM pcm = PCM.load(path,driver);
			if(pcm != null) {
				return pcm;
			}			
		}
		return null;
	}
	
	/**
	 * サンプリングレートを変更したPCMを返す
	 * 
	 * @param sample
	 *            サンプリングレート
	 * @return サンプリングレート変更後のPCM
	 */
	public abstract PCM<T> changeSampleRate(int sample);

	/**
	 * 再生速度を変更したPCMを返す
	 * 
	 * @param rate
	 *            再生速度。基準は1.0
	 * @return 再生速度を変更したPCM
	 */
	public abstract PCM<T> changeFrequency(float rate);
	
	/**
	 * チャンネル数を変更したPCMを返す
	 * 
	 * @param channels
	 *            チャンネル数
	 * @return チャンネル数を変更したPCM
	 */
	public abstract PCM<T> changeChannels(int channels);
	/**
	 * トリミングしたPCMを返す
	 * 
	 * @param starttime
	 *            開始時間(us)
	 * @param duration
	 *            再生時間(us)
	 * @return トリミングしたPCM
	 */
	public abstract PCM<T> slice(long starttime, long duration);
	
	public abstract boolean validate();
	
	protected static ByteBuffer getDirectByteBuffer(int capacity) {
		ByteBuffer result = USE_UNSAFE ? BufferUtils.newUnsafeByteBuffer(capacity) : ByteBuffer.allocateDirect(capacity);
		return result.order(ByteOrder.LITTLE_ENDIAN);
	}

	static class PCMLoader {

		/** Largest WAV data chunk allocated up front from its declared length. */
		static final int MAX_PREALLOCATED_WAV_BYTES = 64 * 1024 * 1024;

		ByteBuffer pcm;
		int channels = 0;
		int sampleRate = 0;
		int bitsPerSample = 0;
		
		private final AudioDriver driver;
		
		public PCMLoader(AudioDriver driver) {
			this.driver = driver;
		};
		
		public void loadPCM(Path p) throws IOException {
			loadPCM(SongResources.fromPath(p));
		}

		public void loadPCM(SongResource resource) throws IOException {
			IOException failure;
			try (InputStream input = resource.openStream()) {
				loadPCM(input, resource.displayPath());
				return;
			} catch (IOException e) {
				failure = e;
			}
			// The extension did not match the content: retry once with the container signature.
			String detected;
			try (InputStream input = resource.openStream()) {
				detected = detectFormat(input.readNBytes(12));
			}
			if (detected == null || resource.name().toLowerCase(Locale.ROOT).endsWith(detected)) {
				throw failure;
			}
			try (InputStream input = resource.openStream()) {
				loadPCM(input, resource.displayPath(), detected);
			}
		}

		/** Audio extension implied by the first bytes, or null when no supported container matches. */
		static String detectFormat(byte[] h) {
			if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
					&& h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E') {
				return ".wav";
			}
			if (h.length >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') {
				return ".ogg";
			}
			if (h.length >= 4 && h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') {
				return ".flac";
			}
			if (h.length >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
				return ".mp3";
			}
			// MPEG audio frame sync with a valid layer, bitrate index and sample-rate index.
			if (h.length >= 3 && (h[0] & 0xff) == 0xff && (h[1] & 0xe0) == 0xe0 && (h[1] & 0x06) != 0
					&& (h[2] & 0xf0) != 0xf0 && (h[2] & 0x0c) != 0x0c) {
				return ".mp3";
			}
			return null;
		}

		public void loadPCM(InputStream source, String name) throws IOException {
			loadPCM(source, name, null);
		}

		private void loadPCM(InputStream source, String name, String format) throws IOException {
			// final long time = System.nanoTime();
			pcm = null;

			final String lowerName = format != null ? format : name.toLowerCase(Locale.ROOT);
			if (lowerName.endsWith(".wav")) {
				try (WavInputStream input = new WavInputStream(new BufferedInputStream(source))) {
					switch(input.type) {
						case 1, 3 -> {
							// Reject headers that would divide by zero or decode as silence/garbage.
							if (input.channels <= 0 || input.sampleRate <= 0
									|| (input.bitsPerSample != 8 && input.bitsPerSample != 16
											&& input.bitsPerSample != 24 && input.bitsPerSample != 32)) {
								throw new IOException(name + " invalid WAV format : channels=" + input.channels
										+ " sampleRate=" + input.sampleRate + " bitsPerSample=" + input.bitsPerSample);
							}
							channels = input.channels;
							sampleRate = input.sampleRate;
							bitsPerSample = input.bitsPerSample;

							// A declared data length is only a hint: truncated files end early and
							// streaming writers leave 0 or 0xFFFFFFFF. Never pre-allocate more than the cap.
							final int declared = input.dataRemaining;
							if(bitsPerSample == 16 && declared > 0 && declared <= MAX_PREALLOCATED_WAV_BYTES) {
								pcm = getDirectByteBuffer(declared);
								StreamUtils.copyStream(input, pcm);
								// WavInputStream counts down what it delivered; a short file stops early.
								pcm.limit(declared - input.dataRemaining);
							} else {
								OptimizedByteArrayOutputStream output = new OptimizedByteArrayOutputStream(
										declared > 0 && declared <= MAX_PREALLOCATED_WAV_BYTES ? declared : 65536);
								StreamUtils.copyStream(input, output);
								if (bitsPerSample == 16) {
									pcm = getDirectByteBuffer(output.size()).put(output.getBuffer(), 0, output.size());
								} else {
									pcm = ByteBuffer.wrap(output.getBuffer()).order(ByteOrder.LITTLE_ENDIAN);
									pcm.limit(output.size());
								}
							}
						}
						// mp3
						case 85 -> {
							try {
								Bitstream bitstream = new Bitstream(new ByteArrayInputStream(StreamUtils.copyStreamToByteArray(
										input, Math.min(input.dataRemaining, MAX_PREALLOCATED_WAV_BYTES))));
								ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
								MP3Decoder decoder = new MP3Decoder();
								OutputBuffer outputBuffer = null;
								while (true) {
									Header header = bitstream.readFrame();
									if (header == null)
										break;
									if (outputBuffer == null) {
										channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
										outputBuffer = new OutputBuffer(channels, false);
										decoder.setOutputBuffer(outputBuffer);
										sampleRate = header.getSampleRate();
									}
									try {
										decoder.decodeFrame(header, bitstream);
									} catch (Exception ignored) {
										// JLayer's decoder throws
										// ArrayIndexOutOfBoundsException
										// sometimes!?
									}
									bitstream.closeFrame();
									output.write(outputBuffer.getBuffer(), 0, outputBuffer.reset());
								}
								bitstream.close();
								byte[] bytes = output.toByteArray();
								pcm = getDirectByteBuffer(bytes.length).put(bytes);
								bitsPerSample = 16;
							} catch (BitstreamException e) {
								e.printStackTrace();
							}
						}
					default -> throw new IOException(name + " unsupported WAV format ID : " + input.type);
					}
				} catch (Throwable e) {
					Logger.getGlobal().warning("WAV処理中の例外 - file : " + name + " error : "+ e.getMessage());
				}
			} else if (lowerName.endsWith(".ogg")) {
				// ogg
				try (OggInputStream input = new OggInputStream(new BufferedInputStream(source))) {
					// final long time = System.nanoTime();
					// OptimizedByteArrayOutputStream output = new
					// OptimizedByteArrayOutputStream(4096);
					OptimizedByteArrayOutputStream output = new OptimizedByteArrayOutputStream(input.getLength() * 16);
					byte[] buff = new byte[4096];
					while (!input.atEnd()) {
						int length = input.read(buff);
						if (length == -1)
							break;
						output.write(buff, 0, length);
					}
					
					channels = input.getChannels();
					sampleRate = input.getSampleRate();
					bitsPerSample = 16;
					
					pcm = getDirectByteBuffer(output.size()).put(output.getBuffer(), 0, output.size());
//					System.out.println(name + " - length : " + input.getLength() + " ( " + input.getLength() * 16 + " ) " + " , bytes : " + bytes);
				} catch (Throwable ex) {
				}
			} else if (lowerName.endsWith(".mp3")) {
				// mp3
				try {
					Bitstream bitstream = new Bitstream(new BufferedInputStream(source));
					ByteArrayOutputStream output = new ByteArrayOutputStream(4096);
					MP3Decoder decoder = new MP3Decoder();
					OutputBuffer outputBuffer = null;
					while (true) {
						Header header = bitstream.readFrame();
						if (header == null)
							break;
						if (outputBuffer == null) {
							channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
							outputBuffer = new OutputBuffer(channels, false);
							decoder.setOutputBuffer(outputBuffer);
							sampleRate = header.getSampleRate();
						}
						try {
							decoder.decodeFrame(header, bitstream);
						} catch (Exception ignored) {
							// JLayer's decoder throws
							// ArrayIndexOutOfBoundsException
							// sometimes!?
						}
						bitstream.closeFrame();
						output.write(outputBuffer.getBuffer(), 0, outputBuffer.reset());
					}
					bitstream.close();
					byte[] bytes = output.toByteArray();
					pcm = getDirectByteBuffer(bytes.length).put(bytes);
					bitsPerSample = 16;
				} catch (Throwable ex) {
				}
			} else if (lowerName.endsWith(".flac")) {
				// flac
				try {
					FLACDecoder input = new FLACDecoder(new BufferedInputStream(source));
					input.readMetadata();
					StreamInfo info = input.getStreamInfo();
					
					channels = info.getChannels();
					sampleRate = info.getSampleRate();
					bitsPerSample = info.getBitsPerSample();
					
					OptimizedByteArrayOutputStream output = new OptimizedByteArrayOutputStream((int)info.getTotalSamples() * 16);
					input.addPCMProcessor(new FlacProcessor(output));
					
					input.decodeFrames();
					
					if(bitsPerSample == 16) {
						pcm = getDirectByteBuffer(output.size()).put(output.getBuffer(), 0, output.size());						
					} else {
						pcm = ByteBuffer.wrap(output.getBuffer()).order(ByteOrder.LITTLE_ENDIAN);
						pcm.limit(output.size());						
					}
				} catch (Throwable ex) {
					ex.printStackTrace();
				}
			}

			if(pcm == null) {
				throw new IOException(name + " : can't convert to PCM");
			}
			
			int bytes = pcm.limit();
			bytes -= bytes % (channels > 1 ? bitsPerSample / 4 : bitsPerSample / 8);
//			final int orgbytes = bytes;
			while(bytes > channels * bitsPerSample / 8) {
				boolean zero = true;
				for(int i = 0;i < channels * bitsPerSample / 8;i++){
					zero &= (pcm.get(bytes - i - 1) == 0x00);
				}
				if(zero) {
					bytes -= channels * bitsPerSample / 8;
				} else {
					break;
				}
			}
//			if(bytes != orgbytes) {
//				Logger.getGlobal().info("終端の無音データ除外 - " + p.getFileName().toString() + " : " + (orgbytes - bytes) + " bytes");
//			}
			if(bytes < channels * bitsPerSample / 8) {
				throw new IOException(name + " : 0 samples");
			}
			if(sampleRate == 0) {
				throw new IOException(name + " : 0 sample rate");
			}
			pcm.limit(bytes);
			convertToDriverFormat();
			
//			System.out.println(p.getFileName().toString() + " - " + sampleRate + " Hz, " + bitsPerSample + " bits, " + channels + " channels");
		}

		private void convertToDriverFormat() {
			if (!(driver instanceof AbstractAudioDriver<?> audioDriver)) {
				return;
			}

			int preferredChannels = audioDriver.channels;
			if (preferredChannels != 0 && preferredChannels != channels) {
				pcm = changeChannels(pcm, channels, preferredChannels, bitsPerSample);
				channels = preferredChannels;
			}

			int preferredSampleRate = audioDriver.getSampleRate();
			if (preferredSampleRate != 0 && preferredSampleRate != sampleRate) {
				pcm = changeSampleRate(pcm, channels, sampleRate, preferredSampleRate, bitsPerSample);
				sampleRate = preferredSampleRate;
			}
		}

		private static ByteBuffer changeChannels(ByteBuffer source, int sourceChannels, int targetChannels, int bitsPerSample) {
			int bytesPerSample = bitsPerSample / 8;
			int sourceFrames = source.limit() / (sourceChannels * bytesPerSample);
			ByteBuffer output = allocatePCMBuffer(sourceFrames * targetChannels * bytesPerSample, bitsPerSample);

			for (int frame = 0; frame < sourceFrames; frame++) {
				double sample = readSample(source, frame * sourceChannels, bitsPerSample);
				for (int channel = 0; channel < targetChannels; channel++) {
					writeSample(output, frame * targetChannels + channel, bitsPerSample, sample);
				}
			}
			return output;
		}

		private static ByteBuffer changeSampleRate(ByteBuffer source, int channels, int sourceSampleRate, int targetSampleRate, int bitsPerSample) {
			int bytesPerSample = bitsPerSample / 8;
			int sourceFrames = source.limit() / (channels * bytesPerSample);
			int targetFrames = (int) (((long) sourceFrames) * targetSampleRate / sourceSampleRate);
			ByteBuffer output = allocatePCMBuffer(targetFrames * channels * bytesPerSample, bitsPerSample);

			for (long frame = 0; frame < targetFrames; frame++) {
				long position = frame * sourceSampleRate / targetSampleRate;
				long mod = (frame * sourceSampleRate) % targetSampleRate;
				for (int channel = 0; channel < channels; channel++) {
					double sample = readSample(source, (int) (position * channels + channel), bitsPerSample);
					if (mod != 0 && (int) ((position + 1) * channels + channel) < sourceFrames * channels) {
						double nextSample = readSample(source, (int) ((position + 1) * channels + channel), bitsPerSample);
						sample = (sample * (targetSampleRate - mod) + nextSample * mod) / targetSampleRate;
					}
					writeSample(output, (int) (frame * channels + channel), bitsPerSample, sample);
				}
			}
			return output;
		}

		private static ByteBuffer allocatePCMBuffer(int capacity, int bitsPerSample) {
			return bitsPerSample == 16
					? getDirectByteBuffer(capacity)
					: ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
		}

		private static double readSample(ByteBuffer source, int sampleIndex, int bitsPerSample) {
			int offset = sampleIndex * bitsPerSample / 8;
			return switch (bitsPerSample) {
				case 8 -> (source.get(offset) & 0xff) - 128;
				case 16 -> source.getShort(offset);
				case 24 -> read24BitSample(source, offset);
				case 32 -> source.getFloat(offset);
				default -> 0;
			};
		}

		private static int read24BitSample(ByteBuffer source, int offset) {
			int sample = (source.get(offset) & 0xff) | ((source.get(offset + 1) & 0xff) << 8) | ((source.get(offset + 2) & 0xff) << 16);
			return (sample & 0x800000) != 0 ? sample | 0xff000000 : sample;
		}

		private static void writeSample(ByteBuffer output, int sampleIndex, int bitsPerSample, double sample) {
			int offset = sampleIndex * bitsPerSample / 8;
			switch (bitsPerSample) {
				case 8 -> output.put(offset, (byte) (clamp((int) sample + 128, 0, 255) & 0xff));
				case 16 -> output.putShort(offset, (short) clamp((int) sample, Short.MIN_VALUE, Short.MAX_VALUE));
				case 24 -> write24BitSample(output, offset, clamp((int) sample, -0x800000, 0x7fffff));
				case 32 -> output.putFloat(offset, (float) sample);
				default -> {
				}
			}
		}

		private static void write24BitSample(ByteBuffer output, int offset, int sample) {
			output.put(offset, (byte) (sample & 0xff));
			output.put(offset + 1, (byte) ((sample >>> 8) & 0xff));
			output.put(offset + 2, (byte) ((sample >>> 16) & 0xff));
		}

		private static int clamp(int value, int min, int max) {
			return Math.max(min, Math.min(max, value));
		}
		
	}
	
	/** @author Nathan Sweet */
	private static class WavInputStream extends FilterInputStream {
		private int dataRemaining;
		/**
		 * PCMのタイプ
		 */
		private final int type;
		private final int channels;
		private final int sampleRate;
		/**
		 * 1サンプル当たりのビット数
		 */
		private final int bitsPerSample;		

		WavInputStream(InputStream p) {
			super(p);
			try {
				if (read() != 'R' || read() != 'I' || read() != 'F' || read() != 'F')
					throw new RuntimeException("RIFF header not found: " + p.toString());

				skipFully(4);

				if (read() != 'W' || read() != 'A' || read() != 'V' || read() != 'E')
					throw new RuntimeException("Invalid wave file header: " + p.toString());

				int fmtChunkLength = seekToChunk('f', 'm', 't', ' ');
				if (fmtChunkLength < 16)
					throw new IOException("Invalid fmt chunk length: " + fmtChunkLength);

				type = readByte() | readByte() << 8;

				channels = readByte() | readByte() << 8;

				sampleRate = readByte() | readByte() << 8 | readByte() << 16 | readByte() << 24;

				skipFully(6);

				bitsPerSample = readByte() | readByte() << 8;

				skipFully(fmtChunkLength - 16);
				skipPad(fmtChunkLength);

				int dataLength = seekToChunk('d', 'a', 't', 'a');
				// 0xFFFFFFFF (or any size beyond int) is written by streaming encoders: read until EOF.
				dataRemaining = dataLength < 0 ? Integer.MAX_VALUE : dataLength;
			} catch (Throwable ex) {
				StreamUtils.closeQuietly(this);
				throw new RuntimeException("Error reading WAV file: " + p.toString(), ex);
			}
		}

		/** Reads one header byte; end of file inside a header is an error, not a 0xff value. */
		private int readByte() throws IOException {
			int value = read();
			if (value < 0)
				throw new EOFException("Truncated WAV header");
			return value;
		}

		private int seekToChunk(char c1, char c2, char c3, char c4) throws IOException {
			while (true) {
				boolean found = readByte() == c1;
				found &= readByte() == c2;
				found &= readByte() == c3;
				found &= readByte() == c4;
				int chunkLength = readByte() | readByte() << 8 | readByte() << 16 | readByte() << 24;
				if (found)
					return chunkLength;
				if (chunkLength < 0)
					throw new IOException("Invalid chunk length before: " + c1 + c2 + c3 + c4);
				skipFully(chunkLength);
				skipPad(chunkLength);
			}
		}

		/**
		 * RIFF chunks are word aligned, so an odd-sized chunk is followed by a zero pad byte.
		 * Some writers omit it; a chunk ID never starts with 0, so only a zero byte is consumed.
		 */
		private void skipPad(int chunkLength) throws IOException {
			if ((chunkLength & 1) == 0 || !in.markSupported())
				return;
			in.mark(1);
			if (in.read() != 0)
				in.reset();
		}

		private void skipFully(int count) throws IOException {
			while (count > 0) {
				long skipped = in.skip(count);
				if (skipped <= 0)
					throw new EOFException("Unable to skip.");
				count -= skipped;
			}
		}

		public int read(byte[] buffer) throws IOException {
			if (dataRemaining == 0)
				return -1;
			int length = Math.min(super.read(buffer), dataRemaining);
			if (length == -1)
				return -1;
			dataRemaining -= length;
			return length;
		}
	}
}
