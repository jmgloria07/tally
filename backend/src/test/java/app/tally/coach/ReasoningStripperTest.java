package app.tally.coach;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReasoningStripperTest {

    @Test
    void removesCompleteThinkBlocks() {
        assertThat(ReasoningStripper.strip("<think>plan\nmore</think>\n## Answer\nText<think>x</think>"))
                .isEqualTo("## Answer\nText");
    }

    @Test
    void dropsEverythingUpToAnUnmatchedClosingTag() {
        assertThat(ReasoningStripper.strip("reasoning without opening tag</think>\n\n## Answer"))
                .isEqualTo("## Answer");
    }

    @Test
    void dropsTruncatedReasoningAfterAnUnmatchedOpeningTag() {
        assertThat(ReasoningStripper.strip("## Answer\n<think>cut off mid-thought"))
                .isEqualTo("## Answer");
    }

    @Test
    void keepsOnlyTheGptOssFinalChannel() {
        String raw = "<|channel|>analysis<|message|>thinking...<|end|><|start|>assistant"
                + "<|channel|>final<|message|>## Answer";
        assertThat(ReasoningStripper.strip(raw)).isEqualTo("## Answer");
    }

    @Test
    void onlyReasoningBecomesEmpty() {
        assertThat(ReasoningStripper.strip("<think>all reasoning</think>   ")).isEmpty();
        assertThat(ReasoningStripper.strip(null)).isEmpty();
    }
}
