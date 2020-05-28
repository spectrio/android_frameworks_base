/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.media

import android.content.Context
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.R
import com.android.systemui.util.animation.TransitionLayout
import com.android.systemui.util.animation.TransitionLayoutController
import com.android.systemui.util.animation.TransitionViewState
import com.android.systemui.util.animation.MeasurementOutput

/**
 * A class responsible for controlling a single instance of a media player handling interactions
 * with the view instance and keeping the media view states up to date.
 */
class MediaViewController(
    context: Context,
    val mediaHostStatesManager: MediaHostStatesManager
) {

    private var firstRefresh: Boolean = true
    private var transitionLayout: TransitionLayout? = null
    private val layoutController = TransitionLayoutController()
    private var animationDelay: Long = 0
    private var animationDuration: Long = 0
    private var animateNextStateChange: Boolean = false
    private val measurement = MeasurementOutput(0, 0)

    /**
     * A map containing all viewStates for all locations of this mediaState
     */
    private val mViewStates: MutableMap<MediaHostState, TransitionViewState?> = mutableMapOf()

    /**
     * The ending location of the view where it ends when all animations and transitions have
     * finished
     */
    private var currentEndLocation: Int = -1

    /**
     * The ending location of the view where it ends when all animations and transitions have
     * finished
     */
    private var currentStartLocation: Int = -1

    /**
     * The progress of the transition or 1.0 if there is no transition happening
     */
    private var currentTransitionProgress: Float = 1.0f

    /**
     * A temporary state used to store intermediate measurements.
     */
    private val tmpState = TransitionViewState()

    /**
     * A callback for media state changes
     */
    val stateCallback = object : MediaHostStatesManager.Callback {
        override fun onHostStateChanged(location: Int, mediaHostState: MediaHostState) {
            if (location == currentEndLocation || location == currentStartLocation) {
                setCurrentState(currentStartLocation,
                        currentEndLocation,
                        currentTransitionProgress,
                        applyImmediately = false)
            }
        }
    }

    /**
     * The expanded constraint set used to render a expanded player. If it is modified, make sure
     * to call [refreshState]
     */
    val collapsedLayout = ConstraintSet()

    /**
     * The expanded constraint set used to render a collapsed player. If it is modified, make sure
     * to call [refreshState]
     */
    val expandedLayout = ConstraintSet()

    init {
        collapsedLayout.load(context, R.xml.media_collapsed)
        expandedLayout.load(context, R.xml.media_expanded)
        mediaHostStatesManager.addController(this)
    }

    /**
     * Notify this controller that the view has been removed and all listeners should be destroyed
     */
    fun onDestroy() {
        mediaHostStatesManager.removeController(this)
    }

    private fun ensureAllMeasurements() {
        val mediaStates = mediaHostStatesManager.mediaHostStates
        for (entry in mediaStates) {
            obtainViewState(entry.value)
        }
    }

    /**
     * Get the constraintSet for a given expansion
     */
    private fun constraintSetForExpansion(expansion: Float): ConstraintSet =
            if (expansion > 0) expandedLayout else collapsedLayout

    /**
     * Obtain a new viewState for a given media state. This usually returns a cached state, but if
     * it's not available, it will recreate one by measuring, which may be expensive.
     */
    private fun obtainViewState(state: MediaHostState): TransitionViewState? {
        val viewState = mViewStates[state]
        if (viewState != null) {
            // we already have cached this measurement, let's continue
            return viewState
        }

        val result: TransitionViewState?
        if (transitionLayout != null && state.measurementInput != null) {
            // Let's create a new measurement
            if (state.expansion == 0.0f || state.expansion == 1.0f) {
                result = transitionLayout!!.calculateViewState(
                        state.measurementInput!!,
                        constraintSetForExpansion(state.expansion),
                        TransitionViewState())

                // We don't want to cache interpolated or null states as this could quickly fill up
                // our cache. We only cache the start and the end states since the interpolation
                // is cheap
                mViewStates[state.copy()] = result
            } else {
                // This is an interpolated state
                val startState = state.copy().also { it.expansion = 0.0f }

                // Given that we have a measurement and a view, let's get (guaranteed) viewstates
                // from the start and end state and interpolate them
                val startViewState = obtainViewState(startState) as TransitionViewState
                val endState = state.copy().also { it.expansion = 1.0f }
                val endViewState = obtainViewState(endState) as TransitionViewState
                result = TransitionViewState()
                layoutController.getInterpolatedState(
                        startViewState,
                        endViewState,
                        state.expansion,
                        result)
            }
        } else {
            result = null
        }
        return result
    }

    /**
     * Attach a view to this controller. This may perform measurements if it's not available yet
     * and should therefore be done carefully.
     */
    fun attach(transitionLayout: TransitionLayout) {
        this.transitionLayout = transitionLayout
        layoutController.attach(transitionLayout)
        ensureAllMeasurements()
        if (currentEndLocation == -1) {
            return
        }
        // Set the previously set state immediately to the view, now that it's finally attached
        setCurrentState(
                startLocation = currentStartLocation,
                endLocation = currentEndLocation,
                transitionProgress = currentTransitionProgress,
                applyImmediately = true)
    }

    /**
     * Obtain a measurement for a given location. This makes sure that the state is up to date
     * and all widgets know their location. Calling this method may create a measurement if we
     * don't have a cached value available already.
     */
    fun getMeasurementsForState(hostState: MediaHostState): MeasurementOutput? {
        val viewState = obtainViewState(hostState) ?: return null
        measurement.measuredWidth = viewState.width
        measurement.measuredHeight = viewState.height
        return measurement
    }

    /**
     * Set a new state for the controlled view which can be an interpolation between multiple
     * locations.
     */
    fun setCurrentState(
        @MediaLocation startLocation: Int,
        @MediaLocation endLocation: Int,
        transitionProgress: Float,
        applyImmediately: Boolean
    ) {
        currentEndLocation = endLocation
        currentStartLocation = startLocation
        currentTransitionProgress = transitionProgress

        val shouldAnimate = animateNextStateChange && !applyImmediately

        // Obtain the view state that we'd want to be at the end
        // The view might not be bound yet or has never been measured and in that case will be
        // reset once the state is fully available
        val endState = obtainViewStateForLocation(endLocation) ?: return
        layoutController.setMeasureState(endState)

        // If the view isn't bound, we can drop the animation, otherwise we'll executute it
        animateNextStateChange = false
        if (transitionLayout == null) {
            return
        }

        val startState = obtainViewStateForLocation(startLocation)
        val result: TransitionViewState?
        if (transitionProgress == 1.0f || startState == null) {
            result = endState
        } else if (transitionProgress == 0.0f) {
            result = startState
        } else {
            layoutController.getInterpolatedState(startState, endState, transitionProgress,
                    tmpState)
            result = tmpState
        }
        layoutController.setState(result, applyImmediately, shouldAnimate, animationDuration,
                animationDelay)
    }

    private fun obtainViewStateForLocation(location: Int): TransitionViewState? {
        val mediaState = mediaHostStatesManager.mediaHostStates[location] ?: return null
        return obtainViewState(mediaState)
    }

    /**
     * Notify that the location is changing right now and a [setCurrentState] change is imminent.
     * This updates the width the view will me measured with.
     */
    fun onLocationPreChange(@MediaLocation newLocation: Int) {
        val viewState = obtainViewStateForLocation(newLocation)
        viewState?.let {
            layoutController.setMeasureState(it)
        }
    }

    /**
     * Request that the next state change should be animated with the given parameters.
     */
    fun animatePendingStateChange(duration: Long, delay: Long) {
        animateNextStateChange = true
        animationDuration = duration
        animationDelay = delay
    }

    /**
     * Clear all existing measurements and refresh the state to match the view.
     */
    fun refreshState() {
        if (!firstRefresh) {
            // Let's clear all of our measurements and recreate them!
            mViewStates.clear()
            setCurrentState(currentStartLocation, currentEndLocation, currentTransitionProgress,
                    applyImmediately = false)
        }
        firstRefresh = false
    }
}
