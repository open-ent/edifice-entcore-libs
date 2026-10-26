package org.entcore.common.http.filter;

import java.lang.annotation.*;

@Retention(RetentionPolicy.SOURCE)
@Documented
@Target(ElementType.METHOD)
public @interface Trace {
    String value();
    boolean body() default true;
    int retentionDays() default 365;
    /** Opération sensible : écrite aussi dans le journal d'audit chaîné (voir AuditHelper). */
    boolean audit() default false;
}