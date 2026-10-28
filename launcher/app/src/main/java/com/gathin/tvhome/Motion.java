package com.gathin.tvhome;

import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/** Reduced motion (config.xml reduced_motion): the one spring and the one time limit every animation uses then. */
final class Motion {

    /** The longest any reduced-motion animation runs, in milliseconds. */
    static final int REDUCED_MS = 90;
    /** Critically damped (no bounce) and stiff enough that any move, even across the screen, settles in 5 frames. */
    static final float REDUCED_STIFFNESS = 15000f;
    /** The spring stops once it is within this many pixels of its target. */
    static final float REDUCED_MIN_CHANGE = 2f;

    private Motion() {
    }

    static void reduce(SpringAnimation spring) {
        spring.setSpring(new SpringForce().setStiffness(REDUCED_STIFFNESS).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY));
        spring.setMinimumVisibleChange(REDUCED_MIN_CHANGE);
    }

    /** The focus highlight with focus_overshoot off: critically damped, so it glides onto the tile. */
    static void glide(SpringAnimation spring, boolean advancedEffects) {
        spring.setSpring(new SpringForce().setStiffness(advancedEffects ? 520f : 440f).setDampingRatio(1f));
    }

    /** The focus highlight's spring as the config asks for it: reduced motion, a glide, or the bouncy overshoot. */
    static void focus(SpringAnimation spring, boolean reducedMotion, boolean focusOvershoot, boolean advancedEffects) {
        if (reducedMotion) {
            reduce(spring);
        } else if (!focusOvershoot) {
            glide(spring, advancedEffects);
        } else {
            spring.setSpring(new SpringForce().setStiffness(advancedEffects ? 320f : 260f)
                    .setDampingRatio(advancedEffects ? 0.76f : 0.58f));
        }
    }
}
