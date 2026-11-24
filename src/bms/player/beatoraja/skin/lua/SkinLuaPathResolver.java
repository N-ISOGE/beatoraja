package bms.player.beatoraja.skin.lua;

import bms.player.beatoraja.skin.SkinPathPolicy;
import java.nio.file.Path;
import java.io.IOException;
import java.util.function.Supplier;

import org.luaj.vm2.LuaError;

final class SkinLuaPathResolver {

	private final Supplier<Path> skinDirectorySupplier;

	SkinLuaPathResolver(Supplier<Path> skinDirectorySupplier) {
		this.skinDirectorySupplier = skinDirectorySupplier;
	}

	Path resolve(String pathText) {
		try {
			return SkinPathPolicy.resolve(skinDirectorySupplier.get(), pathText);
		} catch (IOException e) {
			throw new LuaError(e.getMessage());
		}
	}
}
