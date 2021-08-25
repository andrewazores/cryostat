/*
 * Copyright The Cryostat Authors
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or data
 * (collectively the "Software"), free of charge and under any and all copyright
 * rights in the Software, and any and all patent rights owned or freely
 * licensable by each licensor hereunder covering either (i) the unmodified
 * Software as contributed to or provided by such licensor, or (ii) the Larger
 * Works (as defined below), to deal in both
 *
 * (a) the Software, and
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software (each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 * The above copyright notice and either this complete permission notice or at
 * a minimum a reference to the UPL must be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.cryostat.net;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import io.cryostat.MainModule;
import io.cryostat.core.log.Logger;
import io.cryostat.core.sys.FileSystem;
import io.cryostat.net.OpenShiftAuthManager.PermissionDeniedException;
import io.cryostat.net.security.ResourceAction;
import io.cryostat.net.security.ResourceType;
import io.cryostat.net.security.ResourceVerb;

import com.google.gson.Gson;
import io.fabric8.kubernetes.api.model.authentication.TokenReview;
import io.fabric8.kubernetes.api.model.authentication.TokenReviewBuilder;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.server.mock.EnableOpenShiftMockClient;
import io.fabric8.openshift.client.server.mock.OpenShiftMockServer;
import io.fabric8.openshift.client.server.mock.OpenShiftMockServerExtension;
import okhttp3.mockwebserver.RecordedRequest;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith({MockitoExtension.class, OpenShiftMockServerExtension.class})
@EnableOpenShiftMockClient(https = false, crud = false)
class OpenShiftAuthManagerTest {

    static final String SUBJECT_REVIEW_API_PATH =
            "/apis/authorization.k8s.io/v1/selfsubjectaccessreviews";
    static final String TOKEN_REVIEW_API_PATH = "/apis/authentication.k8s.io/v1/tokenreviews";

    OpenShiftAuthManager mgr;
    @Mock FileSystem fs;
    @Mock Logger logger;
    OpenShiftClient client;
    OpenShiftMockServer server;
    TokenProvider tokenProvider;
    Gson gson = MainModule.provideGson(logger);

    @BeforeAll
    static void disableKubeConfig() {
        // FIXME Disable reading ~/.kube/config. Remove once fabric8-client updated to 5.5.0 or
        // newer.
        System.setProperty(Config.KUBERNETES_AUTH_TRYKUBECONFIG_SYSTEM_PROPERTY, "false");
        System.setProperty(Config.KUBERNETES_AUTH_TRYSERVICEACCOUNT_SYSTEM_PROPERTY, "false");
    }

    @BeforeEach
    void setup() {
        client = Mockito.spy(client);
        tokenProvider = new TokenProvider(client);
        mgr = new OpenShiftAuthManager(logger, fs, tokenProvider);
    }

    @Test
    void shouldHandleBearerAuthentication() {
        MatcherAssert.assertThat(mgr.getScheme(), Matchers.equalTo(AuthenticationScheme.BEARER));
    }

    @ParameterizedTest
    @NullAndEmptySource
    void shouldNotValidateBlankToken(String tok) throws Exception {
        MatcherAssert.assertThat(
                mgr.validateToken(() -> tok, ResourceAction.NONE).get(), Matchers.is(false));
    }

    @Test
    void shouldValidateTokenWithNoRequiredPermissions() throws Exception {
        TokenReview tokenReview =
                new TokenReviewBuilder()
                        .withNewStatus()
                        .withAuthenticated(true)
                        .endStatus()
                        .build();
        server.expect()
                .post()
                .withPath(TOKEN_REVIEW_API_PATH)
                .andReturn(HttpURLConnection.HTTP_CREATED, tokenReview)
                .once();

        Mockito.when(fs.readFile(Paths.get(Config.KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH)))
                .thenReturn(new BufferedReader(new StringReader("serviceAccountToken")));

        MatcherAssert.assertThat(
                mgr.validateToken(() -> "userToken", ResourceAction.NONE).get(), Matchers.is(true));
    }

    @Test
    void shouldNotValidateTokenWithNoRequiredPermissionsButNoTokenAccess() throws Exception {
        TokenReview tokenReview =
                new TokenReviewBuilder()
                        .withNewStatus()
                        .withAuthenticated(false)
                        .endStatus()
                        .build();
        server.expect()
                .post()
                .withPath(TOKEN_REVIEW_API_PATH)
                .andReturn(HttpURLConnection.HTTP_CREATED, tokenReview)
                .once();

        Mockito.when(fs.readFile(Paths.get(Config.KUBERNETES_SERVICE_ACCOUNT_TOKEN_PATH)))
                .thenReturn(new BufferedReader(new StringReader("serviceAccountToken")));

        MatcherAssert.assertThat(
                mgr.validateToken(() -> "userToken", ResourceAction.NONE).get(),
                Matchers.is(false));
    }

    @Test
    void shouldValidateTokenWithSufficientPermissions() throws Exception {
        SelfSubjectAccessReview accessReview =
                new SelfSubjectAccessReviewBuilder()
                        .withNewStatus()
                        .withAllowed(true)
                        .endStatus()
                        .build();
        server.expect()
                .post()
                .withPath(SUBJECT_REVIEW_API_PATH)
                .andReturn(HttpURLConnection.HTTP_CREATED, accessReview)
                .once();

        Mockito.when(fs.readFile(Paths.get(Config.KUBERNETES_NAMESPACE_PATH)))
                .thenReturn(new BufferedReader(new StringReader("mynamespace")));

        MatcherAssert.assertThat(
                mgr.validateToken(() -> "token", Set.of(ResourceAction.READ_TARGET)).get(),
                Matchers.is(true));

        ArgumentCaptor<Path> nsPathCaptor = ArgumentCaptor.forClass(Path.class);
        Mockito.verify(fs).readFile(nsPathCaptor.capture());
        MatcherAssert.assertThat(
                nsPathCaptor.getValue(),
                Matchers.equalTo(Paths.get(Config.KUBERNETES_NAMESPACE_PATH)));
    }

    @Test
    void shouldNotValidateTokenWithInsufficientPermissions() throws Exception {
        SelfSubjectAccessReview accessReview =
                new SelfSubjectAccessReviewBuilder()
                        .withNewStatus()
                        .withAllowed(false)
                        .endStatus()
                        .build();
        server.expect()
                .post()
                .withPath(SUBJECT_REVIEW_API_PATH)
                .andReturn(HttpURLConnection.HTTP_CREATED, accessReview)
                .once();

        String namespace = "mynamespace";
        Mockito.when(fs.readFile(Paths.get(Config.KUBERNETES_NAMESPACE_PATH)))
                .thenReturn(new BufferedReader(new StringReader(namespace)));

        ExecutionException ee =
                Assertions.assertThrows(
                        ExecutionException.class,
                        () ->
                                mgr.validateToken(() -> "token", Set.of(ResourceAction.READ_RECORDING))
                                        .get());
        MatcherAssert.assertThat(
                ExceptionUtils.getRootCause(ee),
                Matchers.instanceOf(PermissionDeniedException.class));
        PermissionDeniedException pde = (PermissionDeniedException) ExceptionUtils.getRootCause(ee);
        MatcherAssert.assertThat(pde.getNamespace(), Matchers.equalTo(namespace));
        MatcherAssert.assertThat(pde.getResourceType(), Matchers.equalTo("recordings"));
        MatcherAssert.assertThat(pde.getVerb(), Matchers.equalTo("get"));

        ArgumentCaptor<Path> nsPathCaptor = ArgumentCaptor.forClass(Path.class);
        Mockito.verify(fs).readFile(nsPathCaptor.capture());
        MatcherAssert.assertThat(
                nsPathCaptor.getValue(),
                Matchers.equalTo(Paths.get(Config.KUBERNETES_NAMESPACE_PATH)));
    }

    @ParameterizedTest
    @EnumSource(
            mode = EnumSource.Mode.MATCH_ANY,
            names = "^([a-zA-Z]+_(RECORDING|CERTIFICATE))$")
    void shouldValidateExpectedPermissionsPerSecuredResource(ResourceAction resourceAction)
            throws Exception {
        Mockito.when(fs.readFile(Paths.get(Config.KUBERNETES_NAMESPACE_PATH)))
                .thenReturn(new BufferedReader(new StringReader("mynamespace")));

        String expectedVerb;
        if (resourceAction.getVerb() == ResourceVerb.CREATE) {
            expectedVerb = "create";
        } else if (resourceAction.getVerb() == ResourceVerb.READ) {
            expectedVerb = "get";
        } else if (resourceAction.getVerb() == ResourceVerb.UPDATE) {
            expectedVerb = "patch";
        } else if (resourceAction.getVerb() == ResourceVerb.DELETE) {
            expectedVerb = "delete";
        } else {
            throw new IllegalArgumentException(resourceAction.getVerb().toString());
        }

        Set<String> expectedResources;
        if (resourceAction.getResource() == ResourceType.RECORDING) {
            expectedResources = Set.of("recordings");
        } else if (resourceAction.getResource() == ResourceType.CERTIFICATE) {
            expectedResources = Set.of("deployments", "pods");
        } else {
            throw new IllegalArgumentException(resourceAction.getResource().toString());
        }

        SelfSubjectAccessReview accessReview =
                new SelfSubjectAccessReviewBuilder()
                        .withNewStatus()
                        .withAllowed(true)
                        .endStatus()
                        .build();
        server.expect()
                .post()
                .withPath(SUBJECT_REVIEW_API_PATH)
                .andReturn(HttpURLConnection.HTTP_CREATED, accessReview)
                .times(expectedResources.size());

        String token = "abcd1234";
        MatcherAssert.assertThat(
                mgr.validateToken(() -> token, Set.of(resourceAction)).get(), Matchers.is(true));

        // server.takeRequest() returns each request fired in order, so do that repeatedly and drop
        // any initial requests that are made by the OpenShiftClient that aren't directly
        // SelfSubjectAccessReview requests made by the OpenShiftAuthManager
        int maxDroppedRequests = 2;
        int requestCount = 0;
        RecordedRequest req = server.takeRequest();
        while (true) {
            if (++requestCount > maxDroppedRequests) {
                throw new IllegalStateException();
            }
            String path = req.getPath();
            if (SUBJECT_REVIEW_API_PATH.equals(path)) {
                break;
            }
            req = server.takeRequest();
        }
        MatcherAssert.assertThat(req.getPath(), Matchers.equalTo(SUBJECT_REVIEW_API_PATH));
        MatcherAssert.assertThat(tokenProvider.token, Matchers.equalTo(token));
        MatcherAssert.assertThat(req.getMethod(), Matchers.equalTo("POST"));

        SelfSubjectAccessReview body =
                gson.fromJson(req.getBody().readUtf8(), SelfSubjectAccessReview.class);
        MatcherAssert.assertThat(
                body.getSpec().getResourceAttributes().getVerb(), Matchers.equalTo(expectedVerb));

        Set<String> actualResources = new HashSet<>();
        actualResources.add(body.getSpec().getResourceAttributes().getResource());
        // start at 1 because we've already checked the first request above
        for (int i = 1; i < expectedResources.size(); i++) {
            // request should already have been made, so there should be no time waiting for a
            // request to come in
            req = server.takeRequest(1, TimeUnit.SECONDS);
            if (req == null) {
                throw new IllegalStateException("Expected request not received in time");
            }
            body = gson.fromJson(req.getBody().readUtf8(), SelfSubjectAccessReview.class);

            MatcherAssert.assertThat(req.getPath(), Matchers.equalTo(SUBJECT_REVIEW_API_PATH));
            MatcherAssert.assertThat(tokenProvider.token, Matchers.equalTo(token));
            MatcherAssert.assertThat(req.getMethod(), Matchers.equalTo("POST"));
            MatcherAssert.assertThat(
                    body.getSpec().getResourceAttributes().getVerb(),
                    Matchers.equalTo(expectedVerb));
            actualResources.add(body.getSpec().getResourceAttributes().getResource());
        }

        MatcherAssert.assertThat(actualResources, Matchers.equalTo(expectedResources));
    }

    @ParameterizedTest
    @EnumSource(
            mode = EnumSource.Mode.MATCH_ALL,
            names = {
                "^[a-zA-Z]+_(?!RECORDING).*$",
                "^[a-zA-Z]+_(?!CERTIFICATE).*$",
            })
    void shouldValidateExpectedPermissionsForUnsecuredResources(ResourceAction resourceAction)
            throws Exception {
        Mockito.when(fs.readFile(Paths.get(Config.KUBERNETES_NAMESPACE_PATH)))
                .thenReturn(new BufferedReader(new StringReader("mynamespace")));
        MatcherAssert.assertThat(
                mgr.validateToken(() -> "token", Set.of(resourceAction)).get(), Matchers.is(true));
    }

    private static class TokenProvider implements Function<String, OpenShiftClient> {

        private final OpenShiftClient osc;
        String token;

        TokenProvider(OpenShiftClient osc) {
            this.osc = osc;
        }

        @Override
        public OpenShiftClient apply(String token) {
            if (this.token != null) {
                throw new IllegalStateException("Token was already set!");
            }
            this.token = token;
            return osc;
        }
    }
}
