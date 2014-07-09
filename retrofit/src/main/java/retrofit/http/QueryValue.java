/*
 * Copyright (C) 2014 Square, Inc.
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
 * limitations under the License.
 */
package retrofit.http;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Key-less query parameter appended to the URL.
 * <p>
 * Values are converted to strings using {@link String#valueOf(Object)} and then URL encoded.
 * {@code null} values are ignored. Passing a {@link java.util.List List} or array will result in a
 * query parameter for each non-{@code null} item.
 * <p>
 * Simple Example:
 * <pre>
 * &#64;GET("/list")
 * void list(@QueryValue int page);
 * </pre>
 * Calling with {@code foo.list(24)} yields {@code /list?24}.
 * <p>
 * Example with {@code null}:
 * <pre>
 * &#64;GET("/list")
 * void list(@QueryValue String category);
 * </pre>
 * Calling with {@code foo.list(null)} yields {@code /list}.
 * <p>
 * Array Example:
 * <pre>
 * &#64;GET("/list")
 * void list(@QueryValue String... categories);
 * </pre>
 * Calling with {@code foo.list("bar", "baz")} yields
 * {@code /list?foo&bar}.
 *
 * @see EncodedQueryValue
 * @see Query
 * @see QueryMap
 */
@Documented
@Target(PARAMETER)
@Retention(RUNTIME)
public @interface QueryValue {
}
