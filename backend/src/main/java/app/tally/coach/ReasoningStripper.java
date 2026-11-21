package app.tally.coach;

import java.util.regex.Pattern;

/**
 * Removes model reasoning from a reply so only the final answer reaches the user (spec §8.6). Handles Qwen3
 * {@code <think>} blocks, templates that inject the opening tag into the prompt, truncated output, and
 * gpt-oss channel markup when LM Studio is not separating reasoning.
 */
public final class ReasoningStripper {

    private static final Pattern THINK_BLOCK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);
    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";
    private static final String GPT_OSS_FINAL = "<|channel|>final<|message|>";

    private ReasoningStripper() {
    }

    public static String strip(String content) {
        if (content == null) {
            return "";
        }
        String text = THINK_BLOCK.matcher(content).replaceAll("");
        int close = text.lastIndexOf(CLOSE);
        if (close >= 0) {
            text = text.substring(close + CLOSE.length());
        }
        int open = text.indexOf(OPEN);
        if (open >= 0) {
            text = text.substring(0, open);
        }
        int finalMarker = text.lastIndexOf(GPT_OSS_FINAL);
        if (finalMarker >= 0) {
            text = text.substring(finalMarker + GPT_OSS_FINAL.length());
        }
        return text.strip();
    }
}
