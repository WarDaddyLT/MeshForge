package dev.meshforge.fieldflasher;

import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

final class FlashJournal {
    enum Stage { PREPARING, STAGED, PREPARED, WRITING, DELIVERED }

    private final SharedPreferences storage;

    FlashJournal(SharedPreferences storage) {
        this.storage = storage;
    }

    synchronized void start(String board, String name, String sha, boolean uf2) {
        storage.edit().clear().putString("board", board).putString("name", name)
                .putString("sha", sha).putBoolean("uf2", uf2)
                .putInt("stage", Stage.PREPARING.ordinal()).commit();
    }

    synchronized void advance(Stage stage, String sha) {
        int current = storage.getInt("stage", -1);
        if (current < 0 || stage.ordinal() <= current) return;
        var editor = storage.edit().putInt("stage", stage.ordinal());
        if (sha != null && sha.matches("[a-f0-9]{64}")) editor.putString("sha", sha);
        editor.commit();
    }

    synchronized JSONObject recovery() throws JSONException {
        int stage = storage.getInt("stage", -1);
        if (stage < 0 || stage >= Stage.values().length) return null;
        return new JSONObject().put("stage", Stage.values()[stage].name())
                .put("board", storage.getString("board", ""))
                .put("name", storage.getString("name", ""))
                .put("sha", storage.getString("sha", ""))
                .put("uf2", storage.getBoolean("uf2", false));
    }

    synchronized void clear() {
        storage.edit().clear().commit();
    }
}
