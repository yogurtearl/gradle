/*
 * Copyright 2021 the original author or authors.
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

package org.gradle.internal;

import org.gradle.util.internal.GUtil;

import java.net.URI;

/**
 * For enforcing use of HTTPS.
 */
public interface HttpsVerifier {

    /**
     * Verifies that the URL can be used.
     *
     * Callers should throw context sensitive exceptions on a false return.
     */
    boolean allow(URI uri);

    static HttpsVerifier create(boolean allowUnsafe) {
        return allowUnsafe ? ALLOW_ALL : DISALLOW_HTTP;
    }

    /**
     * Allows any type of URL.
     */
    HttpsVerifier ALLOW_ALL = uri -> true;

    /**
     * Allows HTTPS, or HTTP to 127.0.0.1
     */
    HttpsVerifier DISALLOW_HTTP = GUtil::isSecureUrl;

}
