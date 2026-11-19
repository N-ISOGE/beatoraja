package bms.player.beatoraja.skin.lua;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

import bms.player.beatoraja.MainState;
import org.luaj.vm2.LuaTable;

/**
 * 実行時にスキンからMainStateの数値などにアクセスできる関数を提供する
 */
public class MainStateAccessor implements com.badlogic.gdx.utils.Disposable {

	private final List<LuaApiExporter> exporters;
	private final boolean allowExternalEffects;

	public MainStateAccessor(MainState state) {
		this(state, () -> null);
	}

	public MainStateAccessor(MainState state, Supplier<Path> skinDirectorySupplier) {
		this(state, skinDirectorySupplier, true);
	}

	MainStateAccessor(MainState state, Supplier<Path> skinDirectorySupplier, boolean allowExternalEffects) {
		SkinLuaPathResolver pathResolver = new SkinLuaPathResolver(skinDirectorySupplier);
		var apis = new java.util.ArrayList<LuaApiExporter>();
		apis.add(new MainStatePropertyLuaApiExporter(state));
		apis.add(new SkinFileLuaApiExporter(pathResolver, allowExternalEffects));
		apis.add(new SkinRuntimeLuaApiExporter(allowExternalEffects));
		if (allowExternalEffects) {
			apis.add(new SkinHttpLuaApiExporter());
			apis.add(new SkinAudioLuaApiExporter(state, pathResolver));
		}
		exporters = List.copyOf(apis);
		this.allowExternalEffects = allowExternalEffects;
	}

	public void export(LuaTable table) {
		for(LuaApiExporter exporter : exporters) {
			exporter.export(table);
		}
		if (!allowExternalEffects) {
			for (String name : List.of("set_timer", "event_exec", "set_volume_sys", "set_volume_key", "set_volume_bg")) {
				table.set(name, LuaTable.NIL);
			}
		}
	}

	@Override public void dispose() {
		for (LuaApiExporter exporter : exporters) {
			if (exporter instanceof com.badlogic.gdx.utils.Disposable disposable) disposable.dispose();
		}
	}
}
