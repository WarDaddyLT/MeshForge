package dev.meshforge.fieldflasher;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class RepeaterTables {
    private static final String ROOT = "*";
    private static final int REGION_LIMIT = 156;

    record Region(String name, String parent, boolean flood) {}
    record Regions(Map<String, Region> nodes, String home, String scope, boolean editable) {
        JSONObject json() throws JSONException {
            var list = new JSONArray();
            for (Region row : nodes.values()) {
                list.put(new JSONObject().put("name", row.name()).put("parent", row.parent())
                        .put("flood", row.flood()));
            }
            return new JSONObject().put("list", list).put("home", home)
                    .put("scope", scope == null ? JSONObject.NULL : scope).put("editable", editable);
        }
    }

    static Map<String, Integer> readAcl(RepeaterCli cli) throws IOException {
        String reply = cli.table("get acl", false);
        if (reply == null || !reply.startsWith("ACL:\n")) return null;
        var values = new LinkedHashMap<String, Integer>();
        for (String line : reply.substring(5).split("\n")) {
            if (line.isEmpty()) continue;
            if (!line.matches("[0-9a-fA-F]{1,2} [0-9a-fA-F]{64}")) return null;
            var parts = line.split(" ");
            if (values.put(parts[1].toLowerCase(Locale.ROOT), Integer.parseInt(parts[0], 16)) != null) return null;
        }
        return values;
    }

    static Map<String, Integer> aclInput(JSONArray input, Map<String, Integer> previous)
            throws IOException, JSONException {
        if (previous == null) throw new IOException("Access control is unavailable on this radio.");
        if (input.length() > 256) throw new IOException("The access list is too large.");
        var result = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < input.length(); i++) {
            var row = input.getJSONObject(i);
            String key = row.getString("key").strip().toLowerCase(Locale.ROOT);
            int role = row.getInt("role");
            int old = previous.getOrDefault(key, 0);
            if (!key.matches("[0-9a-f]{64}") || role < 0 || role > 3 || role == 0 && (old == 0 || (old & 3) != 0)) {
                throw new IOException("Each access entry needs a valid public key and access role.");
            }
            if (result.put(key, (old & ~3) | role) != null) throw new IOException("Each public key can appear once in the access list.");
        }
        return result;
    }

    static void writeAcl(RepeaterCli cli, Map<String, Integer> previous, Map<String, Integer> next) throws IOException {
        // Update existing and remove obsolete entries before adding keys, so a full ACL can be edited.
        for (String key : previous.keySet()) {
            int value = next.getOrDefault(key, 0);
            if (value != previous.get(key)) setPermission(cli, key, value);
        }
        for (String key : next.keySet()) {
            if (!previous.containsKey(key)) setPermission(cli, key, next.get(key));
        }
        // The firmware batches the flash write after changes to the in-memory ACL.
        SystemClock.sleep(6000);
        if (!next.equals(readAcl(cli))) throw new IOException("The access list could not be verified after saving.");
    }

    private static void setPermission(RepeaterCli cli, String key, int value) throws IOException {
        RepeaterCli.expectOk(cli.command("setperm " + key + " " + value, "set access level"), "Access list update");
    }

    static Regions readRegions(RepeaterCli cli) throws IOException {
        String reply = cli.table("region", true);
        if (reply == null || !reply.startsWith(ROOT)) return null;
        String scope = scope(cli.command("region default", "get default region"), "default scope is ");
        var nodes = new LinkedHashMap<String, Region>();
        var stack = new ArrayList<String>();
        String home = ROOT;
        boolean complete = reply.length() < REGION_LIMIT;
        for (String line : reply.split("\n")) {
            if (line.isBlank()) continue;
            String label = line.stripLeading();
            int depth = line.length() - label.length();
            boolean flood = label.endsWith(" F");
            if (flood) label = label.substring(0, label.length() - 2);
            boolean isHome = label.endsWith("^");
            if (isHome) label = label.substring(0, label.length() - 1);
            if (depth > stack.size() || (nodes.isEmpty() ? depth != 0 || !ROOT.equals(label) :
                    depth == 0 || !validName(label)) || nodes.containsKey(label)) {
                complete = false;
                break;
            }
            while (stack.size() > depth) stack.remove(stack.size() - 1);
            String parent = depth == 0 ? "" : stack.get(depth - 1);
            nodes.put(label, new Region(label, parent, flood));
            stack.add(label);
            if (isHome) home = label;
        }
        if (scope != null && !scope.isEmpty() && !nodes.containsKey(scope)) complete = false;
        if (nodes.isEmpty()) return null;
        return new Regions(nodes, home, scope, complete);
    }

    static Regions regionInput(JSONObject input, Regions previous) throws IOException, JSONException {
        if (previous == null || !previous.editable()) throw new IOException("The radio did not return a complete region list.");
        var array = input.getJSONArray("list");
        if (array.length() == 0 || array.length() > 64) throw new IOException("The region list size is invalid.");
        var rows = new LinkedHashMap<String, Region>();
        for (int i = 0; i < array.length(); i++) {
            var item = array.getJSONObject(i);
            String name = item.getString("name").strip();
            String parent = item.getString("parent");
            if (name.startsWith("#")) name = name.substring(1);
            if (parent.startsWith("#")) parent = parent.substring(1);
            if (!ROOT.equals(name) && !validName(name) || ROOT.equals(name) && !parent.isEmpty() ||
                    rows.put(name, new Region(name, parent, item.getBoolean("flood"))) != null) {
                throw new IOException("Use unique region names of up to 30 letters, numbers, hyphens, # or $.");
            }
        }
        if (!rows.containsKey(ROOT)) throw new IOException("Unscoped traffic is required in the region list.");
        String home = cleanScope(input.getString("home"));
        String scope = previous.scope() == null ? null : cleanScope(input.getString("scope"));
        if (!rows.containsKey(home) || scope != null && !scope.isEmpty() && !rows.containsKey(scope)) {
            throw new IOException("Choose home and default regions from the region list.");
        }
        int outputLength = 0;
        for (Region row : rows.values()) {
            int depth = depth(rows, row.name());
            outputLength += depth + row.name().length() + (row.flood() ? 2 : 0) +
                    (row.name().equals(home) ? 1 : 0) + 1;
        }
        if (outputLength >= REGION_LIMIT) throw new IOException("This region tree is too large for the radio to return in full.");
        return new Regions(rows, home, scope, true);
    }

    static void writeRegions(RepeaterCli cli, Regions previous, Regions next) throws IOException {
        var order = new ArrayList<>(next.nodes().values());
        order.sort(Comparator.comparingInt(row -> depthUnchecked(next.nodes(), row.name())));
        var expectedFlood = new LinkedHashMap<String, Boolean>();
        for (Region row : order) {
            Region old = previous.nodes().get(row.name());
            if (!ROOT.equals(row.name()) && (old == null || !row.parent().equals(old.parent()))) {
                String parent = ROOT.equals(row.parent()) ? "" : " " + row.parent();
                RepeaterCli.expectOk(cli.command("region put " + row.name() + parent, "upsert region"), "Region update");
                expectedFlood.put(row.name(), true);
            } else {
                expectedFlood.put(row.name(), old.flood());
            }
        }
        if (!Objects.equals(next.scope(), previous.scope())) {
            String name = next.scope().isEmpty() ? "<null>" : next.scope();
            verifyScope(cli.command("region default " + name, "set default region"), "default scope is now ", next.scope());
            if (!next.scope().isEmpty()) expectedFlood.put(next.scope(), true);
        }
        if (!next.home().equals(previous.home())) {
            verifyScope(cli.command("region home " + next.home(), "set home region"), "home is now ", next.home());
        }
        var removals = new ArrayList<>(previous.nodes().keySet());
        removals.removeAll(next.nodes().keySet());
        removals.sort(Comparator.comparingInt((String name) -> depthUnchecked(previous.nodes(), name)).reversed());
        for (String name : removals) {
            RepeaterCli.expectOk(cli.command("region remove " + name, "remove region"), "Region removal");
        }
        for (Region row : order) {
            if (expectedFlood.get(row.name()) != row.flood()) {
                String verb = row.flood() ? "allowf" : "denyf";
                RepeaterCli.expectOk(cli.command("region " + verb + " " + row.name(), "set region forwarding"),
                        "Region forwarding");
            }
        }
        RepeaterCli.expectOk(cli.command("region save", "save regions"), "Region save");
        if (!next.equals(readRegions(cli))) throw new IOException("The region list could not be verified after saving.");
    }

    private static int depth(Map<String, Region> rows, String name) throws IOException {
        var seen = new HashSet<String>();
        while (!ROOT.equals(name)) {
            Region row = rows.get(name);
            if (row == null || !seen.add(name)) throw new IOException("Each region parent must lead to unscoped traffic without a loop.");
            name = row.parent();
        }
        return seen.size();
    }

    private static int depthUnchecked(Map<String, Region> rows, String name) {
        int depth = 0;
        while (!ROOT.equals(name)) {
            name = rows.get(name).parent();
            depth++;
        }
        return depth;
    }

    private static String cleanScope(String name) { return name.startsWith("#") ? name.substring(1) : name; }

    private static boolean validName(String name) { return name.matches("[A-Za-z0-9$#-]{1,30}"); }

    private static String scope(String reply, String prefix) {
        if (reply == null || !reply.strip().startsWith(prefix)) return null;
        String name = reply.strip().substring(prefix.length());
        return name.equals("<null>") ? "" : cleanScope(name);
    }

    private static void verifyScope(String reply, String prefix, String expected) throws IOException {
        if (!expected.equals(scope(reply, prefix))) throw new IOException("The radio did not confirm the region selection.");
    }
}
