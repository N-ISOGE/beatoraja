package bms.player.beatoraja.skin.lua;

import java.io.InputStreamReader;
import java.io.BufferedReader;
import bms.player.beatoraja.skin.SkinPathPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;

final class SkinFileLuaApiExporter implements LuaApiExporter {

	private final SkinLuaPathResolver pathResolver;
	private final boolean allowExternalEffects;

	SkinFileLuaApiExporter(SkinLuaPathResolver pathResolver) {
		this(pathResolver, true);
	}

	SkinFileLuaApiExporter(SkinLuaPathResolver pathResolver, boolean allowExternalEffects) {
		this.pathResolver = pathResolver;
		this.allowExternalEffects = allowExternalEffects;
	}

	@Override
	public void export(LuaTable table) {
		table.set("file_delete", new OneArgFunction() {
			@Override public LuaValue call(LuaValue path) {
				if (!allowExternalEffects) return LuaValue.FALSE;
				try {
					Path resolved = pathResolver.resolve(path.checkjstring());
					return LuaValue.valueOf(Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
							&& Files.deleteIfExists(resolved));
				} catch (IOException | RuntimeException e) { return LuaValue.FALSE; }
			}
		});
		table.set("file_exists", new OneArgFunction() {
			@Override
			public LuaValue call(LuaValue path) {
				return LuaBoolean.valueOf(Files.exists(pathResolver.resolve(path.tojstring())));
			}
		});
		table.set("file_mkdir", new OneArgFunction() {
			@Override
			public LuaValue call(LuaValue path) {
				if (!allowExternalEffects) return LuaValue.FALSE;
				try {
					Files.createDirectories(pathResolver.resolve(path.tojstring()));
					return LuaBoolean.TRUE;
				} catch(IOException | RuntimeException e) {
					return LuaBoolean.FALSE;
				}
			}
		});
		table.set("file_list", new VarArgFunction() {
			@Override
			public Varargs invoke(Varargs args) {
				return list(args);
			}
		});
		table.set("file_read_lines", new OneArgFunction() {
			@Override
			public LuaValue call(LuaValue path) {
				return readLines(path.tojstring());
			}
		});
		table.set("file_write", new TwoArgFunction() {
			@Override
			public LuaValue call(LuaValue path, LuaValue text) {
				return LuaBoolean.valueOf(write(path.tojstring(), text.tojstring(), false));
			}
		});
		table.set("file_append", new TwoArgFunction() {
			@Override
			public LuaValue call(LuaValue path, LuaValue text) {
				return LuaBoolean.valueOf(write(path.tojstring(), text.tojstring(), true));
			}
		});
		table.set("file_clear", new OneArgFunction() {
			@Override
			public LuaValue call(LuaValue path) {
				return LuaBoolean.valueOf(write(path.tojstring(), "", false));
			}
		});
		table.set("file_count_lines", new OneArgFunction() {
			@Override
			public LuaValue call(LuaValue path) {
				return LuaInteger.valueOf(countLines(path.tojstring()));
			}
		});
	}

	private Varargs list(Varargs args) {
		try {
			Path directory = pathResolver.resolve(args.checkjstring(1));
			String patternText = args.narg() >= 2 && !args.isnil(2) ? args.tojstring(2) : null;
			if (patternText != null && patternText.length() > 256) throw new IOException("Skin pattern too long");
			Pattern pattern = patternText != null && !patternText.isEmpty() ? Pattern.compile(toJavaRegex(patternText)) : null;
			StringBuilder paths = new StringBuilder();
			int count = 0;
			int visited = 0;
			var budget = new MatchBudget();
			try(DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
				for(Path path : stream) {
					if (++visited > 4096) throw new IOException("Too many skin directory entries");
					try {
						SkinPathPolicy.requireInside(directory, path);
					} catch (IOException e) {
						continue;
					}
					String normalized = path.toString().replace('\\', '/');
					if(pattern == null) {
						paths.append(normalized).append('\n');
						count++;
					} else {
						Matcher matcher = pattern.matcher(budget.limit(normalized));
						if(matcher.find()) {
							paths.append(matcher.group()).append('\n');
							count++;
						}
					}
				}
			}
			return LuaValue.varargsOf(LuaString.valueOf(paths.toString()), LuaInteger.valueOf(count));
		} catch(IOException | RuntimeException | StackOverflowError e) {
			return LuaValue.varargsOf(LuaString.valueOf(""), LuaInteger.ZERO);
		}
	}

	private static final int MAX_LINES = 128 * 1024;

	private BufferedReader openLines(String path) throws IOException {
		return new BufferedReader(new InputStreamReader(
				SkinPathPolicy.open(pathResolver.resolve(path), SkinLuaAccessor.MAX_FILE_BYTES),
				StandardCharsets.UTF_8.newDecoder()));
	}

	private LuaTable readLines(String path) {
		try (BufferedReader reader = openLines(path)) {
			LuaTable lines = new LuaTable();
			int index = 0;
			String line;
			while ((line = reader.readLine()) != null) {
				if (++index > MAX_LINES) throw new IOException("Too many skin file lines");
				lines.set(index, line);
			}
			return lines;
		} catch (IOException | RuntimeException e) {
			return new LuaTable();
		}
	}

	private boolean write(String path, String text, boolean append) {
		if (!allowExternalEffects) return false;
		try {
			Path resolved = pathResolver.resolve(path);
			Path parent = resolved.getParent();
			if(parent != null) {
				Files.createDirectories(parent);
			}
			if(append) {
				Files.writeString(resolved, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
			} else {
				Files.writeString(resolved, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS);
			}
			return true;
		} catch(IOException | RuntimeException e) {
			return false;
		}
	}

	private int countLines(String path) {
		try (BufferedReader reader = openLines(path)) {
			int count = 0;
			while (reader.readLine() != null) {
				if (++count > MAX_LINES) return 0;
			}
			return count;
		} catch (IOException | RuntimeException e) {
			return 0;
		}
	}

	private static final class MatchBudget {
		private int remaining = 1_000_000;

		CharSequence limit(String text) {
			return new CharSequence() {
				@Override public int length() { return text.length(); }
				@Override public char charAt(int index) {
					if (--remaining < 0) throw new IllegalArgumentException("Skin pattern work limit reached");
					return text.charAt(index);
				}
				@Override public CharSequence subSequence(int start, int end) {
					return limit(text.substring(start, end));
				}
				@Override public String toString() { return text; }
			};
		}
	}

	private static String toJavaRegex(String luaPattern) {
		StringBuilder regex = new StringBuilder();
		boolean escaped = false;
		for(int i = 0;i < luaPattern.length();i++) {
			char c = luaPattern.charAt(i);
			if(escaped) {
				regex.append('\\').append(c);
				escaped = false;
			} else if(c == '%') {
				escaped = true;
			} else {
				regex.append(c);
			}
		}
		if(escaped) {
			regex.append('%');
		}
		return regex.toString();
	}
}
