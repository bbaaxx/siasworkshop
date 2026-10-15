package com.siaws.siawsmod.content.agents.build;

/**
 * A blueprint file could not be interpreted. The message names the offending file and the
 * concrete problem; the import folder scanner catches this and skips the file with one WARN.
 */
public class BlueprintParseException extends Exception {
    public BlueprintParseException(String fileName, String message) {
        super(fileName + ": " + message);
    }
}
