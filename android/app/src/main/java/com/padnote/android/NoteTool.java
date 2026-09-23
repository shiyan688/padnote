package com.padnote.android;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One capability the model is allowed to invoke.
 *
 * <p>Tools are defined in PadNote's own terms and converted to a provider wire
 * format separately, so adding a vendor-native protocol later does not require
 * touching tool logic. A model may only call tools registered in
 * {@link NoteToolRegistry}; anything else is rejected before execution.
 *
 * <p>Two invariants matter more than the schema details:
 * <ul>
 *   <li>The model never supplies geometry. It supplies <em>constraints</em>
 *       (relative to the selection, a page band, a named slot) and the engine
 *       resolves them, because the model cannot measure rendered content.</li>
 *   <li>A tool result reports what actually happened — which pages the content
 *       landed on, whether a page was added, which existing flows moved — so the
 *       model reacts to measured facts instead of predicting layout.</li>
 * </ul>
 */
interface NoteTool {

    /** Stable identifier sent to the model. */
    String name();

    /** One-line description of when to use this tool, shown to the model. */
    String description();

    /** JSON Schema for the arguments object, in PadNote's vendor-neutral form. */
    JSONObject parameterSchema() throws JSONException;

    /** How much authority this tool needs before it may run. */
    Permission permission();

    /**
     * Runs the tool.
     *
     * <p>Implementations must not throw for ordinary refusals such as "the
     * content does not fit". Those return a result carrying measured
     * alternatives, letting the model choose on meaning while the engine stays
     * the only authority on whether something fits.
     */
    Result execute(JSONObject arguments, NoteToolContext context);

    /**
     * Authority levels. Reading is free; creating content in empty space is
     * automatic but previewed; touching content the user already made needs
     * explicit consent. Handwriting is deliberately absent — v1 grants the model
     * no power to move ink, because without recognition it cannot know what a
     * stroke cluster means.
     */
    enum Permission {
        READ_ONLY,
        CREATE_IN_FREE_SPACE,
        MODIFY_EXISTING
    }

    /** Outcome of one tool call, serialised back to the model as a tool message. */
    final class Result {
        final boolean ok;
        /** Vendor-neutral payload describing what happened, or why it did not. */
        final JSONObject payload;
        /** Human-readable line for the AI card and the undo entry. */
        final String summary;
        /** True when the document changed and the change needs a preview. */
        final boolean mutatedDocument;

        private Result(boolean ok, JSONObject payload, String summary,
                       boolean mutatedDocument) {
            this.ok = ok;
            this.payload = payload;
            this.summary = summary;
            this.mutatedDocument = mutatedDocument;
        }

        static Result ok(JSONObject payload, String summary) {
            return new Result(true, payload, summary, false);
        }

        static Result mutated(JSONObject payload, String summary) {
            return new Result(true, payload, summary, true);
        }

        /**
         * A refusal the model can act on. {@code payload} should carry measured
         * options rather than only an error string.
         */
        static Result rejected(JSONObject payload, String summary) {
            return new Result(false, payload, summary, false);
        }

        static Result error(String reason) {
            JSONObject payload = new JSONObject();
            try {
                payload.put("error", reason);
            } catch (JSONException ignored) {
                // A result that cannot serialise its own error still reports the summary.
            }
            return new Result(false, payload, reason, false);
        }
    }
}
