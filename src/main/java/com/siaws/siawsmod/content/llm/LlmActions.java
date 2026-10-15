package com.siaws.siawsmod.content.llm;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The action contract between the LLM and the world (design pillar 2, "Action space (the
 * contract)"): the model may only pick ONE action from this validated space, expressed as a
 * small JSON object {@code {"action":"...","args":{...}}}. {@link #validate(JsonObject)} is
 * the safety boundary — raw model output never touches the world without passing through
 * here first; anything unlisted or malformed is rejected and the caller falls back to
 * templates. Action names match the observation's AVAILABLE ACTIONS section.
 */
public final class LlmActions {
    /** One validated action: a canonical name plus its (shape-checked) arguments. */
    public record LlmAction(String action, JsonObject args) {}

    private static final Map<String, ArgShape> ACTION_SPACE = new LinkedHashMap<>();

    static {
        ACTION_SPACE.put("move", ArgShape.XZ);             // move(x,z)
        ACTION_SPACE.put("follow", ArgShape.PLAYER);       // follow(player)
        ACTION_SPACE.put("gather", ArgShape.BLOCK);        // gather(block)
        ACTION_SPACE.put("chop", ArgShape.NONE);           // chop()
        ACTION_SPACE.put("mine", ArgShape.BLOCK);          // mine(block)
        ACTION_SPACE.put("craft", ArgShape.ITEM);          // craft(item)
        ACTION_SPACE.put("place", ArgShape.BLOCK);         // place(block)
        ACTION_SPACE.put("build", ArgShape.BLUEPRINT);     // build(blueprint)
        ACTION_SPACE.put("deposit", ArgShape.CHEST);       // deposit(chest)
        ACTION_SPACE.put("withdraw", ArgShape.CHEST);      // withdraw(chest)
        ACTION_SPACE.put("light", ArgShape.NONE);          // light()
        ACTION_SPACE.put("use", ArgShape.ITEM);            // use(item)
        ACTION_SPACE.put("say", ArgShape.SAY);             // say(player,msg)
        ACTION_SPACE.put("rest", ArgShape.NONE);           // rest()
        ACTION_SPACE.put("claim", ArgShape.CLAIM);         // claim([task]) — optional task key
    }

    private LlmActions() {}

    /** The canonical action space, in display order. */
    public static List<String> validActions() {
        return List.copyOf(ACTION_SPACE.keySet());
    }

    /**
     * Validates a parsed candidate object. Returns the action when the name is in the space
     * and the args have the required shape; null otherwise (caller falls back to templates).
     */
    @Nullable
    public static LlmAction validate(JsonObject candidate) {
        if (candidate == null || !candidate.has("action") || !candidate.get("action").isJsonPrimitive()) {
            return null;
        }
        String name = candidate.get("action").getAsString();
        ArgShape shape = ACTION_SPACE.get(name);
        if (shape == null) {
            return null;
        }
        JsonObject args = candidate.has("args") && candidate.get("args").isJsonObject()
                ? candidate.getAsJsonObject("args")
                : new JsonObject();
        return shape.check(args) ? new LlmAction(name, args) : null;
    }

    private enum ArgShape {
        NONE,
        XZ {
            @Override
            boolean check(JsonObject args) {
                return isNumber(args, "x") && isNumber(args, "z");
            }
        },
        PLAYER {
            @Override
            boolean check(JsonObject args) {
                return isString(args, "player");
            }
        },
        BLOCK {
            @Override
            boolean check(JsonObject args) {
                return isString(args, "block");
            }
        },
        ITEM {
            @Override
            boolean check(JsonObject args) {
                return isString(args, "item");
            }
        },
        BLUEPRINT {
            @Override
            boolean check(JsonObject args) {
                return isString(args, "blueprint");
            }
        },
        CHEST {
            @Override
            boolean check(JsonObject args) {
                return isString(args, "chest");
            }
        },
        SAY {
            @Override
            boolean check(JsonObject args) {
                return isString(args, "player") && isString(args, "msg");
            }
        },
        CLAIM {
            @Override
            boolean check(JsonObject args) {
                // task key is optional; present means the model picked a specific open task
                return !args.has("task") || isString(args, "task");
            }
        };

        boolean check(JsonObject args) {
            return args.size() == 0;
        }

        static boolean isString(JsonObject args, String key) {
            if (!args.has(key) || !args.get(key).isJsonPrimitive()) {
                return false;
            }
            try {
                args.get(key).getAsString();
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        static boolean isNumber(JsonObject args, String key) {
            if (!args.has(key) || !args.get(key).isJsonPrimitive()) {
                return false;
            }
            try {
                args.get(key).getAsNumber();
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /** Parses the content string of a chat-completion reply; null when it is not a JSON object. */
    @Nullable
    public static JsonObject parseJsonObject(String content) {
        if (content == null) {
            return null;
        }
        String trimmed = content.strip();
        if (trimmed.isEmpty() || trimmed.charAt(0) != '{') {
            return null;
        }
        try {
            JsonElement element = com.google.gson.JsonParser.parseString(trimmed);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
