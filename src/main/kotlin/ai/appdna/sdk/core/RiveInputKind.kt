package ai.appdna.sdk.core

/*
 * WHAT KIND OF RIVE INPUT AN AUTHORED VALUE MEANS, as one rule both platforms call.
 *
 * Rive distinguishes three input kinds — boolean, number and trigger — and the SDK only ever
 * receives JSON, which has no way to say which one is meant. So the kind is inferred from the VALUE
 * TYPE, and the two platforms inferred it differently:
 *
 *   authored value      iOS before            Android before
 *   true / false        boolean input         boolean input
 *   42 / 3.5            number input          number input
 *   "go"                TRIGGER               ignored entirely
 *
 * One authored document, two behaviours — and the ignored case is the silent one: the animation
 * plays its default state and nothing says the input did nothing.
 *
 * 🔴 A STRING MEANS A TRIGGER, which is iOS's reading and the one kept. A trigger has no value to
 * carry, so there is nothing else a string could sensibly mean, and an author who writes
 * `{"celebrate": "now"}` against a trigger input has no other way to say it: the alternative is
 * that triggers are unauthorable, which is what this platform shipped.
 *
 * 🔴 This platform ALSO bailed before any of this when no `state_machine` was named —
 * `block.state_machine ?: return` — so an authored input did nothing at all unless the machine was
 * named too. Its setters need the name, so the requirement is real; what was wrong is that it was
 * silent. Both platforms log the skip now, and the console says the field is required for inputs.
 */
enum class RiveInputKind {
    BOOLEAN,
    NUMBER,
    TRIGGER,
    ;

    companion object {
        /** The kind an authored JSON value means. `null` is not an instruction and sets nothing. */
        @JvmStatic
        fun of(value: Any?): RiveInputKind? = when (value) {
            null -> null
            is Boolean -> BOOLEAN
            is Number -> NUMBER
            else -> TRIGGER
        }
    }
}
