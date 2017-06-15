/* Copyright 2002-2017 CS Systèmes d'Information
 * Licensed to CS Systèmes d'Information (CS) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * CS licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.orekit.frames;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.hipparchus.Field;
import org.hipparchus.RealFieldElement;
import org.orekit.errors.OrekitException;
import org.orekit.errors.OrekitExceptionWrapper;
import org.orekit.time.AbsoluteDate;
import org.orekit.time.FieldAbsoluteDate;
import org.orekit.utils.AngularDerivativesFilter;
import org.orekit.utils.CartesianDerivativesFilter;
import org.orekit.utils.GenericTimeStampedCache;

/** Transform provider using thread-safe interpolation on transforms sample.
 * <p>
 * The interpolation is a polynomial Hermite interpolation, which
 * can either use or ignore the derivatives provided by the raw
 * provider. This means that simple raw providers that do not compute
 * derivatives can be used, the derivatives will be added appropriately
 * by the interpolation process.
 * </p>
 * @see GenericTimeStampedCache
 * @see ShiftingTransformProvider
 * @author Luc Maisonobe
 */
public class InterpolatingTransformProvider implements TransformProvider {

    /** Serializable UID. */
    private static final long serialVersionUID = 20140723L;

    /** Provider for raw (non-interpolated) transforms. */
    private final TransformProvider rawProvider;

    /** Filter for Cartesian derivatives to use in interpolation. */
    private final CartesianDerivativesFilter cFilter;

    /** Filter for angular derivatives to use in interpolation. */
    private final AngularDerivativesFilter aFilter;

    /** Earliest supported date. */
    private final AbsoluteDate earliest;

    /** Latest supported date. */
    private final AbsoluteDate latest;

    /** Grid points time step. */
    private final double step;

    /** Cache for sample points. */
    private final transient GenericTimeStampedCache<Transform> cache;

    /** Field caches for sample points. */
    // we use Object as the value of fieldCaches because despite numerous attempts,
    // we could not find a way to use GenericTimeStampedCache<FieldTransform<? extends RealFieldElement<?>>
    // without the compiler complaining
    private final transient Map<Field<? extends RealFieldElement<?>>, Object> fieldCaches;

    /** Simple constructor.
     * @param rawProvider provider for raw (non-interpolated) transforms
     * @param cFilter filter for derivatives from the sample to use in interpolation
     * @param aFilter filter for derivatives from the sample to use in interpolation
     * @param earliest earliest supported date
     * @param latest latest supported date
     * @param gridPoints number of interpolation grid points
     * @param step grid points time step
     * @param maxSlots maximum number of independent cached time slots
     * in the {@link GenericTimeStampedCache time-stamped cache}
     * @param maxSpan maximum duration span in seconds of one slot
     * in the {@link GenericTimeStampedCache time-stamped cache}
     * @param newSlotInterval time interval above which a new slot is created
     * in the {@link GenericTimeStampedCache time-stamped cache}
     */
    public InterpolatingTransformProvider(final TransformProvider rawProvider,
                                          final CartesianDerivativesFilter cFilter,
                                          final AngularDerivativesFilter aFilter,
                                          final AbsoluteDate earliest, final AbsoluteDate latest,
                                          final int gridPoints, final double step,
                                          final int maxSlots, final double maxSpan, final double newSlotInterval) {
        this.rawProvider = rawProvider;
        this.cFilter     = cFilter;
        this.aFilter     = aFilter;
        this.earliest    = earliest;
        this.latest      = latest;
        this.step        = step;
        this.cache       = new GenericTimeStampedCache<Transform>(gridPoints, maxSlots, maxSpan, newSlotInterval,
                                                                  new TransformGenerator(gridPoints,
                                                                                         rawProvider,
                                                                                         step));
        this.fieldCaches = new HashMap<>();
    }

    /** Get the underlying provider for raw (non-interpolated) transforms.
     * @return provider for raw (non-interpolated) transforms
     */
    public TransformProvider getRawProvider() {
        return rawProvider;
    }

    /** Get the number of interpolation grid points.
     * @return number of interpolation grid points
     */
    public int getGridPoints() {
        return cache.getNeighborsSize();
    }

    /** Get the grid points time step.
     * @return grid points time step
     */
    public double getStep() {
        return step;
    }

    /** {@inheritDoc} */
    @Override
    public Transform getTransform(final AbsoluteDate date) throws OrekitException {
        try {

            // retrieve a sample from the thread-safe cache
            final List<Transform> sample = cache.getNeighbors(date).collect(Collectors.toList());

            // interpolate to specified date
            return Transform.interpolate(date, cFilter, aFilter, sample);

        } catch (OrekitExceptionWrapper oew) {
            // something went wrong while generating the sample,
            // we just forward the exception up
            throw oew.getException();
        }
    }

    /** {@inheritDoc} */
    @Override
    public <T extends RealFieldElement<T>> FieldTransform<T> getTransform(final FieldAbsoluteDate<T> date)
        throws OrekitException {
        try {

            @SuppressWarnings("unchecked")
            GenericTimeStampedCache<FieldTransform<T>> fieldCache =
                (GenericTimeStampedCache<FieldTransform<T>>) fieldCaches.get(date.getField());
            if (fieldCache == null) {
                fieldCache =
                    new GenericTimeStampedCache<FieldTransform<T>>(cache.getNeighborsSize(),
                                                                   cache.getMaxSlots(),
                                                                   cache.getMaxSpan(),
                                                                   cache.getNewSlotQuantumGap(),
                                                                   new FieldTransformGenerator<>(date.getField(),
                                                                                                 cache.getNeighborsSize(),
                                                                                                 rawProvider,
                                                                                                 step));
                fieldCaches.put(date.getField(), fieldCache);
            }

            // retrieve a sample from the thread-safe cache
            final Stream<FieldTransform<T>> sample = fieldCache.getNeighbors(date.toAbsoluteDate());

            // interpolate to specified date
            return FieldTransform.interpolate(date, cFilter, aFilter, sample);

        } catch (OrekitExceptionWrapper oew) {
            // something went wrong while generating the sample,
            // we just forward the exception up
            throw oew.getException();
        }
    }

    /** Replace the instance with a data transfer object for serialization.
     * <p>
     * This intermediate class serializes only the data needed for generation,
     * but does <em>not</em> serializes the cache itself (in fact the cache is
     * not serializable).
     * </p>
     * @return data transfer object that will be serialized
     */
    private Object writeReplace() {
        return new DTO(rawProvider, cFilter.getMaxOrder(), aFilter.getMaxOrder(),
                       earliest, latest, cache.getNeighborsSize(), step,
                       cache.getMaxSlots(), cache.getMaxSpan(), cache.getNewSlotQuantumGap());
    }

    /** Internal class used only for serialization. */
    private static class DTO implements Serializable {

        /** Serializable UID. */
        private static final long serialVersionUID = 20140723L;

        /** Provider for raw (non-interpolated) transforms. */
        private final TransformProvider rawProvider;

        /** Cartesian derivatives to use in interpolation. */
        private final int cDerivatives;

        /** Angular derivatives to use in interpolation. */
        private final int aDerivatives;

        /** Earliest supported date. */
        private final AbsoluteDate earliest;

        /** Latest supported date. */
        private final AbsoluteDate latest;

        /** Number of grid points. */
        private final int gridPoints;

        /** Grid points time step. */
        private final double step;

        /** Maximum number of independent cached time slots. */
        private final int maxSlots;

        /** Maximum duration span in seconds of one slot. */
        private final double maxSpan;

        /** Time interval above which a new slot is created. */
        private final double newSlotInterval;

        /** Simple constructor.
         * @param rawProvider provider for raw (non-interpolated) transforms
         * @param cDerivatives derivation order for Cartesian coordinates
         * @param aDerivatives derivation order for angular coordinates
         * @param earliest earliest supported date
         * @param latest latest supported date
         * @param gridPoints number of interpolation grid points
         * @param step grid points time step
         * @param maxSlots maximum number of independent cached time slots
         * in the {@link GenericTimeStampedCache time-stamped cache}
         * @param maxSpan maximum duration span in seconds of one slot
         * in the {@link GenericTimeStampedCache time-stamped cache}
         * @param newSlotInterval time interval above which a new slot is created
         * in the {@link GenericTimeStampedCache time-stamped cache}
         */
        private DTO(final TransformProvider rawProvider, final int cDerivatives, final int aDerivatives,
                    final AbsoluteDate earliest, final AbsoluteDate latest,
                    final int gridPoints, final double step,
                    final int maxSlots, final double maxSpan, final double newSlotInterval) {
            this.rawProvider      = rawProvider;
            this.cDerivatives     = cDerivatives;
            this.aDerivatives     = aDerivatives;
            this.earliest         = earliest;
            this.latest           = latest;
            this.gridPoints       = gridPoints;
            this.step             = step;
            this.maxSlots         = maxSlots;
            this.maxSpan          = maxSpan;
            this.newSlotInterval  = newSlotInterval;
        }

        /** Replace the deserialized data transfer object with a {@link InterpolatingTransformProvider}.
         * @return replacement {@link InterpolatingTransformProvider}
         */
        private Object readResolve() {
            // build a new provider, with an empty cache
            return new InterpolatingTransformProvider(rawProvider,
                                                      CartesianDerivativesFilter.getFilter(cDerivatives),
                                                      AngularDerivativesFilter.getFilter(aDerivatives),
                                                      earliest, latest, gridPoints, step,
                                                      maxSlots, maxSpan, newSlotInterval);
        }

    }

}
