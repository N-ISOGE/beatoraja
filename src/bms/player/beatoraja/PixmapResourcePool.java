package bms.player.beatoraja;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.utils.BufferUtils;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import bms.player.beatoraja.song.SongResource;
import bms.player.beatoraja.song.SongResources;

/**
 * Pixmapリソースプール
 * 
 * @author exch
 */
public class PixmapResourcePool extends ResourcePool<String, PixmapResourcePool.PixmapResource> {

	/** Point-in-time estimate for native Pixmap memory retained by this pool. */
	public record MemoryStatistics(int pixmapCount, long estimatedNativeBytes, int songResourceCount) {
	}

	public static final class PixmapResource {
		private final Pixmap pixmap;
		private final int frameCount;
		private final int frameWidth;
		private final int frameHeight;
		private final int columns;
		private final int rows;

		public PixmapResource(Pixmap pixmap) {
			this(pixmap, 1, pixmap.getWidth(), pixmap.getHeight(), 1, 1);
		}

		public PixmapResource(Pixmap pixmap, int frameCount, int frameWidth, int frameHeight, int columns, int rows) {
			this.pixmap = pixmap;
			this.frameCount = frameCount;
			this.frameWidth = frameWidth;
			this.frameHeight = frameHeight;
			this.columns = columns;
			this.rows = rows;
		}

		public Pixmap getPixmap() {
			return pixmap;
		}

		public int getFrameCount() {
			return frameCount;
		}

		public int getFrameWidth() {
			return frameWidth;
		}

		public int getFrameHeight() {
			return frameHeight;
		}

		public int getColumns() {
			return columns;
		}

		public int getRows() {
			return rows;
		}

		public void dispose() {
			pixmap.dispose();
		}
	}

	public PixmapResourcePool() {
		super(1);
	}
	
	public PixmapResourcePool(int maxgen) {
		super(maxgen);
	}
	
	@Override
	protected PixmapResource load(String path) {
		final PixmapResource resource = loadPictureResource(path);
		return resource != null ? convert(resource) : null;
	}

	public Pixmap getPixmap(String path) {
		PixmapResource resource = get(path);
		return resource != null ? resource.getPixmap() : null;
	}

	public PixmapResource getPixmapResource(String path) {
		return get(path);
	}

	public Pixmap getPixmap(SongResource resource) {
		PixmapResource picture = getPixmapResource(resource);
		return picture != null ? picture.getPixmap() : null;
	}

	public PixmapResource getPixmapResource(SongResource resource) {
		return get(resource.cacheKey(), () -> {
			PixmapResource picture = loadPictureResource(resource);
			return picture != null ? convert(picture) : null;
		});
	}

	public MemoryStatistics getMemoryStatistics() {
		Statistics statistics = getStatistics(resource -> estimatePixmapBytes(resource.getPixmap()));
		return new MemoryStatistics(statistics.resourceCount(), statistics.estimatedBytes(), 0);
	}

	private static long estimatePixmapBytes(Pixmap pixmap) {
		// Pixmap formats used by skin/BGA resources are at most RGBA8888. Keep this
		// deliberately conservative because libGDX does not expose native allocation size.
		return (long) pixmap.getWidth() * pixmap.getHeight() * Integer.BYTES;
	}

	/**
	 * Pixmapをload時に変換する。
	 *
	 * @param pixmap
	 * @return
	 */
	protected Pixmap convert(Pixmap pixmap) {
		return pixmap;
	}

	protected PixmapResource convert(PixmapResource resource) {
		Pixmap original = resource.getPixmap();
		int width = original.getWidth();
		int height = original.getHeight();
		Pixmap pixmap = convert(original);
		if (pixmap == original) {
			return resource;
		}
		// Sprite-sheet layout stays valid only while the converted image keeps its size.
		if (pixmap.getWidth() == width && pixmap.getHeight() == height) {
			return new PixmapResource(pixmap, resource.getFrameCount(), resource.getFrameWidth(),
					resource.getFrameHeight(), resource.getColumns(), resource.getRows());
		}
		return new PixmapResource(pixmap);
	}

	@Override
	protected void dispose(PixmapResource resource) {
		resource.dispose();
	}

	/**
	 * 指定のパスで表現されるファイルを読み込む
	 * @param path イメージファイルのパス
	 * @return イメージ。読めなかった場合またはpathがファイルでない場合はnullを返す
	 */
	public static Pixmap loadPicture(String path) {
		PixmapResource resource = loadPictureResource(path);
		return resource != null ? resource.getPixmap() : null;
	}

	/**
	 * 指定のパスで表現されるファイルを読み込む
	 * @param path イメージファイルのパス
	 * @return イメージリソース。読めなかった場合またはpathがファイルでない場合はnullを返す
	 */
	public static PixmapResource loadPictureResource(String path) {
		try {
			return loadPictureResource(SongResources.fromPath(Path.of(path)));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Loads an image directly from a local, archive, or remote song resource. */
	public static PixmapResource loadPictureResource(SongResource resource) {
		if (resource.localPath().isPresent()) {
			return loadLocalPictureResource(resource.localPath().get().toString());
		}
		try {
			if (!resource.exists() || resource.isDirectory()) {
				return null;
			}
			String lower = resource.name().toLowerCase(Locale.ROOT);
			// libGDX supplies the decoder for these formats; cache only this entry.
			// The local loader still checks the header before using the native decoder.
			if (lower.endsWith(".cim") || lower.endsWith(".tga")) {
				return loadLocalPictureResource(resource.materialize().toString());
			}
			byte[] bytes;
			try (InputStream input = resource.openStream()) {
				bytes = readLimited(input, MAX_IMAGE_BYTES);
			}
			if (lower.endsWith(".webp") || isWebpSignature(bytes)) {
				return loadWebpWithFFmpeg(bytes, resource.displayPath());
			}
			BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
			return image != null ? new PixmapResource(toPixmap(image)) : null;
		} catch (Throwable e) {
			Logger.getGlobal().warning("BGAファイル読み込み失敗。" + resource.displayPath() + " : " + e.getMessage());
			return null;
		}
	}

	private static PixmapResource loadLocalPictureResource(String path) {
		PixmapResource resource = null;
		Pixmap tex = null;
		File f = new File(path);
		if(!f.isFile()) {
			return null;
		}

		// JPEGファイルはlibGDXのネイティブデコーダー(jpgd)を使用しない。
		// 一部のJPEGファイルでネイティブコードのassert()が発動してプロセスごとクラッシュするため。
		// Extensions in BMS packages are unreliable, so JPEG/WebP headers are also blocked.
		// Headerless TGA, CIM and OS/2 BMP have no such signature and keep the native path.
		String lower = path.toLowerCase(Locale.ROOT);
		byte[] header = readHeader(f);
		boolean isJpeg = lower.endsWith(".jpg") || lower.endsWith(".jpeg") || isJpegSignature(header);
		boolean isWebp = lower.endsWith(".webp") || isWebpSignature(header);

		if (!isJpeg && !isWebp) {
			try {
				if(lower.endsWith(".cim")) {
					tex = PixmapIO.readCIM(Gdx.files.internal(path));
				} else {
					tex = new Pixmap(Gdx.files.internal(path));
				}
			} catch (Throwable e) {
				Logger.getGlobal().warning("BGAファイル読み込み失敗。" + e.getMessage());
			}
		}
		if (tex == null) {
			if (!isWebp && !isJpeg) {
				Logger.getGlobal().warning("BGAファイル読み込み再試行:" + path);
			}
			try {
				// TODO 一部のbmsはImageIO.readで失敗する(e.g. past glow)。別の画像デコーダーが必要
				if (isWebp) {
					resource = loadWebpWithFFmpeg(path);
					if (resource == null) {
						BufferedImage bi = hasImageReader("webp") ? ImageIO.read(f) : null;
						if (bi != null) {
							tex = toPixmap(bi);
						} else {
							Logger.getGlobal().warning("WebPファイルを読み込めません : " + path);
						}
					}
				} else {
					BufferedImage bi = ImageIO.read(f);
					if (bi != null) {
						tex = toPixmap(bi);
					}
				}
			} catch (Throwable e) {
				Logger.getGlobal().warning("BGAファイル読み込み失敗。" + e.getMessage());
				e.printStackTrace();
			}
		}

		return resource != null ? resource : tex != null ? new PixmapResource(tex) : null;
	}

	private static boolean hasImageReader(String formatName) {
		Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(formatName);
		return readers.hasNext();
	}

	/** Upper bound for an image read fully into memory from a non-local song resource. */
	static final int MAX_IMAGE_BYTES = 64 * 1024 * 1024;
	static final long MAX_IMAGE_PIXELS = 64L * 1024L * 1024L;

	private static final int HEADER_BYTES = 12;

	static boolean isJpegSignature(byte[] header) {
		return header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
				&& (header[2] & 0xff) == 0xff;
	}

	static boolean isWebpSignature(byte[] header) {
		return header.length >= HEADER_BYTES && header[0] == 'R' && header[1] == 'I' && header[2] == 'F'
				&& header[3] == 'F' && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
	}

	private static byte[] readHeader(File file) {
		try (InputStream input = new FileInputStream(file)) {
			return input.readNBytes(HEADER_BYTES);
		} catch (IOException | SecurityException e) {
			return new byte[0];
		}
	}

	static byte[] readLimited(InputStream input, int limit) throws IOException {
		byte[] bytes = input.readNBytes(limit + 1);
		if (bytes.length > limit) {
			throw new IOException("Image exceeds " + limit + " bytes");
		}
		return bytes;
	}

	/** Frames of the given size that fit one sprite-sheet texture; at least one. */
	static int maxSpriteFrames(int frameWidth, int frameHeight, int maxTextureSize) {
		long columns = Math.max(1, maxTextureSize / Math.max(1, frameWidth));
		long rows = Math.max(1, maxTextureSize / Math.max(1, frameHeight));
		return (int) Math.min(Integer.MAX_VALUE, columns * rows);
	}

	private static PixmapResource loadWebpWithFFmpeg(String path) {
		List<Pixmap> frames = new ArrayList<>();
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(path)) {
			return grabWebpFrames(grabber, frames, path);
		} catch (Throwable e) {
			frames.forEach(Pixmap::dispose);
			Logger.getGlobal().warning("WebPファイル読み込み失敗。" + e.getMessage());
			return null;
		}
	}

	private static PixmapResource loadWebpWithFFmpeg(byte[] bytes, String name) {
		List<Pixmap> frames = new ArrayList<>();
		try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(new ByteArrayInputStream(bytes))) {
			return grabWebpFrames(grabber, frames, name);
		} catch (Throwable e) {
			frames.forEach(Pixmap::dispose);
			Logger.getGlobal().warning("WebPファイル読み込み失敗。" + name + " : " + e.getMessage());
			return null;
		}
	}

	/** Returns whether decoded image dimensions are positive and within texture and pixel budgets. */
	static boolean isValidImageDimensions(int width, int height, int maxTextureSize) {
		if (width <= 0 || height <= 0 || maxTextureSize <= 0) {
			return false;
		}
		if (width > maxTextureSize || height > maxTextureSize) {
			return false;
		}
		long pixels = (long) width * (long) height;
		return pixels <= MAX_IMAGE_PIXELS && pixels <= (long) maxTextureSize * (long) maxTextureSize;
	}

	private static PixmapResource grabWebpFrames(FFmpegFrameGrabber grabber, List<Pixmap> frames, String name)
			throws Exception {
		grabber.start();
		int maxTextureSize = getMaxTextureSize();
		int streamWidth = grabber.getImageWidth();
		int streamHeight = grabber.getImageHeight();
		if (!isValidImageDimensions(streamWidth, streamHeight, maxTextureSize)) {
			Logger.getGlobal().warning("WebP画像サイズが上限を超えているか不正です : "
					+ name + " size=" + streamWidth + "x" + streamHeight + " max=" + maxTextureSize);
			return null;
		}
		Java2DFrameConverter converter = new Java2DFrameConverter();
		int maxFrames = Integer.MAX_VALUE;
		Frame frame;
		while ((frame = grabber.grabImage()) != null) {
			BufferedImage image = converter.convert(frame);
			if (image == null) {
				continue;
			}
			if (frames.isEmpty()) {
				if (!isValidImageDimensions(image.getWidth(), image.getHeight(), maxTextureSize)) {
					Logger.getGlobal().warning("WebPフレームサイズが上限を超えているか不正です : "
							+ name + " size=" + image.getWidth() + "x" + image.getHeight() + " max=" + maxTextureSize);
					return null;
				}
				maxFrames = maxSpriteFrames(image.getWidth(), image.getHeight(), maxTextureSize);
			}
			if (frames.size() >= maxFrames) {
				Logger.getGlobal().warning("WebPのフレーム数が最大テクスチャサイズに収まらないため打ち切ります : "
						+ name + " frames=" + frames.size() + " max=" + maxTextureSize);
				break;
			}
			frames.add(toPixmap(image));
		}
		return toSpriteSheetPixmap(frames, maxTextureSize);
	}

	static PixmapResource toSpriteSheetPixmap(List<Pixmap> frames, int maxTextureSize) {
		if (frames.isEmpty()) {
			return null;
		}

		int frameWidth = 0;
		int frameHeight = 0;
		for (Pixmap frame : frames) {
			frameWidth = Math.max(frameWidth, frame.getWidth());
			frameHeight = Math.max(frameHeight, frame.getHeight());
		}

		int maxColumns = Math.max(1, maxTextureSize / Math.max(1, frameWidth));
		int maxRows = Math.max(1, maxTextureSize / Math.max(1, frameHeight));
		int[] grid = getSpriteSheetGrid(frames.size(), frameWidth, frameHeight, maxColumns, maxRows);
		int columns = grid[0];
		int rows = grid[1];

		long totalWidth = (long) frameWidth * (long) columns;
		long totalHeight = (long) frameHeight * (long) rows;
		if (totalWidth <= 0 || totalHeight <= 0 || totalWidth > maxTextureSize || totalHeight > maxTextureSize
				|| totalWidth * totalHeight > MAX_IMAGE_PIXELS) {
			Logger.getGlobal().warning("WebPのスプライトシートサイズが最大テクスチャサイズを超えています : "
					+ totalWidth + "x" + totalHeight + " max=" + maxTextureSize);
			frames.forEach(Pixmap::dispose);
			frames.clear();
			return null;
		}
		int width = (int) totalWidth;
		int height = (int) totalHeight;
		if (frames.size() > 1) {
			Logger.getGlobal().info("WebPをスプライトシートとして読み込みました : frames=" + frames.size()
					+ " grid=" + columns + "x" + rows + " size=" + width + "x" + height);
		}

		Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
		for (int i = 0; i < frames.size(); i++) {
			Pixmap frame = frames.get(i);
			int x = i % columns * frameWidth;
			int y = i / columns * frameHeight;
			pixmap.drawPixmap(frame, x, y);
		}
		frames.forEach(Pixmap::dispose);
		return new PixmapResource(pixmap, frames.size(), frameWidth, frameHeight, columns, rows);
	}

	private static int[] getSpriteSheetGrid(int frames, int frameWidth, int frameHeight, int maxColumns, int maxRows) {
		int bestColumns = 0;
		int bestRows = 0;
		long bestScore = Long.MAX_VALUE;
		for (int columns = 1; columns <= Math.min(frames, maxColumns); columns++) {
			if (frames % columns != 0) {
				continue;
			}
			int rows = frames / columns;
			if (rows > maxRows) {
				continue;
			}
			long width = (long) frameWidth * columns;
			long height = (long) frameHeight * rows;
			long score = Math.abs(width - height);
			if (score < bestScore) {
				bestColumns = columns;
				bestRows = rows;
				bestScore = score;
			}
		}
		if (bestColumns > 0) {
			return new int[] { bestColumns, bestRows };
		}

		int columns = Math.min(frames, maxColumns);
		int rows = (frames + columns - 1) / columns;
		while (rows > maxRows && columns < Math.min(frames, maxColumns)) {
			columns++;
			rows = (frames + columns - 1) / columns;
		}
		while (columns > 1 && (long) (columns - 1) * frameWidth >= (long) rows * frameHeight
				&& (frames + columns - 2) / (columns - 1) <= maxRows) {
			columns--;
			rows = (frames + columns - 1) / columns;
		}
		return new int[] { columns, rows };
	}

	private static int getMaxTextureSize() {
		try {
			IntBuffer buffer = BufferUtils.newIntBuffer(1);
			Gdx.gl.glGetIntegerv(GL20.GL_MAX_TEXTURE_SIZE, buffer);
			int size = buffer.get(0);
			if (size > 0) {
				return size;
			}
		} catch (Throwable e) {
			Logger.getGlobal().warning("最大テクスチャサイズを取得できません。" + e.getMessage());
		}
		return 4096;
	}

	private static Pixmap toPixmap(BufferedImage image) {
		Pixmap pixmap = new Pixmap(image.getWidth(), image.getHeight(), Pixmap.Format.RGBA8888);
		for(int x = 0;x < image.getWidth();x++) {
			for(int y = 0;y < image.getHeight();y++) {
				int argb = image.getRGB(x, y);
				pixmap.drawPixel(x, y, argb << 8 | (argb >>> 24 & 0x000000ff));
			}
		}
		return pixmap;
	}
}
