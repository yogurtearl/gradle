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

package org.gradle.internal.resource.transport.http;

import com.google.common.annotations.VisibleForTesting;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.gradle.api.internal.DocumentationRegistry;
import org.gradle.internal.UncheckedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import javax.net.ssl.SSLHandshakeException;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

import static org.apache.http.client.protocol.HttpClientContext.REDIRECT_LOCATIONS;

abstract class BaseHttpClientHelper<T extends Closeable> implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseHttpClientHelper.class);

    private final DocumentationRegistry documentationRegistry;
    private final HttpSettings settings;
    private final Function<? super HttpSettings, ? extends T> clientFactory;

    private T client;

    /**
     * Maintains a queue of contexts which are shared between threads when authentication
     * is activated. When a request is performed, it will pick a context from the queue,
     * and create a new one whenever it's not available (which either means it's the first request
     * or that other requests are being performed concurrently). The queue will grow as big as
     * the max number of concurrent requests executed.
     */
    protected final ConcurrentLinkedQueue<HttpContext> sharedContext;

    @VisibleForTesting
    protected BaseHttpClientHelper(DocumentationRegistry documentationRegistry, HttpSettings settings, Function<? super HttpSettings, ? extends T> clientFactory) {
        this.documentationRegistry = documentationRegistry;
        this.clientFactory = clientFactory;
        this.settings = settings;
        this.sharedContext = settings.getAuthenticationSettings().isEmpty() ? null : new ConcurrentLinkedQueue<>();
    }

    /**
     * Strips the {@link URI#getUserInfo() user info} from the {@link URI} making it
     * safe to appear in log messages.
     */
    @VisibleForTesting
    static URI stripUserCredentials(URI uri) {
        try {
            return new URIBuilder(uri).setUserInfo(null).build();
        } catch (URISyntaxException e) {
            throw UncheckedException.throwAsUncheckedException(e, true);
        }
    }

    @Nullable
    protected static URI getEffectiveUri(HttpUriRequest request, HttpContext httpContext) {
        @SuppressWarnings("unchecked")
        List<URI> redirects = (List<URI>) httpContext.getAttribute(REDIRECT_LOCATIONS);
        if (redirects == null || redirects.isEmpty()) {
            return request.getURI();
        } else {
            return redirects.get(redirects.size() - 1);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (client != null) {
            client.close();
            if (sharedContext != null) {
                sharedContext.clear();
            }
        }
    }

    protected <R> R withContext(Function<? super HttpContext, ? extends R> function) throws HttpRequestException {
        if (sharedContext == null) {
            // There's no authentication involved, requests can be done concurrently
            return function.apply(new BasicHttpContext());
        } else {
            HttpContext context = sharedContext.poll();
            if (context == null) {
                context = new BasicHttpContext();
            }
            try {
                return function.apply(context);
            } finally {
                context.removeAttribute(REDIRECT_LOCATIONS);
                sharedContext.add(context);
            }
        }
    }

    protected void logRequest(HttpUriRequest request) {
        LOGGER.debug("Performing HTTP {}: {}", request.getMethod(), stripUserCredentials(request.getURI()));
    }

    protected HttpRequestException toHttpRequestException(HttpUriRequest request, HttpContext httpContext, IOException e) {
        Exception cause = e;
        if (e instanceof SSLHandshakeException) {
            SSLHandshakeException sslException = (SSLHandshakeException) e;
            final String confidence;
            if (sslException.getMessage() != null && sslException.getMessage().contains("protocol_version")) {
                // If we're handling an SSLHandshakeException with the error of 'protocol_version' we know that the server doesn't support this protocol.
                confidence = "The server does not";
            } else {
                // Sometimes the SSLHandshakeException doesn't include the 'protocol_version', even though this is the cause of the error.
                // Tell the user this but with less confidence.
                confidence = "The server may not";
            }
            String message = String.format(
                confidence + " support the client's requested TLS protocol versions: (%s). " +
                    "You may need to configure the client to allow other protocols to be used. " +
                    "See: %s",
                String.join(", ", HttpClientConfigurer.supportedTlsVersions()),
                documentationRegistry.getDocumentationFor("build_environment", "gradle_system_properties")
            );
            cause = new HttpRequestException(message, cause);
        }

        URI effectiveUri = stripUserCredentials(getEffectiveUri(request, httpContext));
        return new HttpRequestException(String.format("Could not %s '%s'.", request.getMethod(), effectiveUri), cause);
    }

    protected synchronized T getClient() {
        if (client == null) {
            client = clientFactory.apply(settings);
        }
        return client;
    }


}
