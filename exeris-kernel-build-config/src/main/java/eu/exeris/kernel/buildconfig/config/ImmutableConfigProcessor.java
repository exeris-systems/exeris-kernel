/*
 * Copyright (C) 2025-2026 Exeris Systems.
 * SPDX-License-Identifier: Apache-2.0
 */
package eu.exeris.kernel.buildconfig.config;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * Compile-time validation processor for {@code @Immutable} config keys (v0.9 Sprint 5).
 *
 * <h2>What it enforces</h2>
 * <p>Unlike {@code RequiresRoleProcessor}, this processor emits no source — it only
 * fails the compilation when an {@code @Immutable} placement violates the contract:
 *
 * <ul>
 *   <li><b>Mutual exclusion</b> — a field carrying both
 *       {@code eu.exeris.kernel.spi.config.Immutable} and
 *       {@code eu.exeris.kernel.spi.config.Dynamic} is a contradiction (sealed vs.
 *       hot-reloadable) and is rejected with {@link Diagnostic.Kind#ERROR}. This is the
 *       primary merge gate for the feature.</li>
 *   <li><b>Element kind</b> — {@code @Immutable} applies to fields only.</li>
 *   <li><b>Sealed shape</b> — an {@code @Immutable} field must be {@code static final};
 *       a non-final field could be mutated at runtime, defeating the annotation.</li>
 * </ul>
 *
 * <h2>SPI coupling</h2>
 * <p>The annotations are read by fully-qualified name; the processor declares no
 * compile-time dependency on {@code exeris-kernel-spi} (mirrors
 * {@code RequiresRoleProcessor}), keeping build-config out of the SPI reactor cycle.
 *
 * @since 0.9.0
 */
@SupportedAnnotationTypes({
        ImmutableConfigProcessor.IMMUTABLE_FQN,
        ImmutableConfigProcessor.DYNAMIC_FQN
})
@SupportedSourceVersion(SourceVersion.RELEASE_25)
public final class ImmutableConfigProcessor extends AbstractProcessor {

    /** Fully-qualified name of the sealed-key annotation. */
    public static final String IMMUTABLE_FQN = "eu.exeris.kernel.spi.config.Immutable";

    /** Fully-qualified name of the hot-reload annotation it is mutually exclusive with. */
    public static final String DYNAMIC_FQN = "eu.exeris.kernel.spi.config.Dynamic";

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            if (IMMUTABLE_FQN.equals(annotation.getQualifiedName().toString())) {
                for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                    validate(element);
                }
            }
        }
        return false; // do not claim the annotations — other processors may also observe them
    }

    private void validate(Element element) {
        Messager messager = processingEnv.getMessager();
        if (element.getKind() != ElementKind.FIELD) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@Immutable is only applicable to fields", element);
            return;
        }
        if (hasAnnotation(element, DYNAMIC_FQN)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "A config key cannot be both @Immutable and @Dynamic — "
                            + "sealed trust anchors must never be hot-reloaded", element);
            return;
        }
        Set<Modifier> modifiers = element.getModifiers();
        if (!modifiers.contains(Modifier.STATIC) || !modifiers.contains(Modifier.FINAL)) {
            messager.printMessage(Diagnostic.Kind.ERROR,
                    "@Immutable field must be declared 'static final' — a mutable field "
                            + "cannot be a sealed trust anchor", element);
        }
    }

    private static boolean hasAnnotation(Element element, String annotationFqn) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            if (annotationFqn.equals(mirror.getAnnotationType().toString())) {
                return true;
            }
        }
        return false;
    }
}
