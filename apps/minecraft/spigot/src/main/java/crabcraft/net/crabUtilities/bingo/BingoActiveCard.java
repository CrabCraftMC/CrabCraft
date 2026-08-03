package crabcraft.net.crabUtilities.bingo;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashSet;
import java.util.Set;

record BingoActiveCard(int id, int number, long startsAt, long endsAt, Set<String> taskIds) {

    static BingoActiveCard fromJson(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        JsonArray tasks = object.getAsJsonArray("taskIds");
        Set<String> taskIds = new HashSet<>();
        tasks.forEach(task -> taskIds.add(task.getAsString()));
        return new BingoActiveCard(
                object.get("id").getAsInt(),
                object.get("number").getAsInt(),
                object.get("startsAt").getAsLong(),
                object.get("endsAt").getAsLong(),
                Set.copyOf(taskIds));
    }

    boolean isLive() {
        long now = System.currentTimeMillis() / 1_000L;
        return startsAt <= now && now < endsAt;
    }

    boolean contains(BingoTask task) {
        return taskIds.contains(task.id());
    }
}
