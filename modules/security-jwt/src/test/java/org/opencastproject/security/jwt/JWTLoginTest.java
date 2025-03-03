/**
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 *
 * The Apereo Foundation licenses this file to you under the Educational
 * Community License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License
 * at:
 *
 *   http://opensource.org/licenses/ecl2.txt
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 *
 */

package org.opencastproject.security.jwt;

import static org.easymock.EasyMock.anyBoolean;
import static org.easymock.EasyMock.anyObject;
import static org.easymock.EasyMock.anyString;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.createNiceMock;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.reset;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.opencastproject.security.api.DefaultOrganization;
import org.opencastproject.security.api.SecurityService;
import org.opencastproject.security.api.UserDirectoryService;
import org.opencastproject.security.impl.jpa.JpaUserReference;
import org.opencastproject.userdirectory.api.UserReferenceProvider;

import com.auth0.jwk.JwkException;
import com.auth0.jwt.algorithms.Algorithm;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.Before;
import org.junit.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedCredentialsNotFoundException;

import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.servlet.http.HttpServletRequest;

/**
 * Tests for {@link JWTRequestHeaderAuthenticationFilter} and {@link DynamicLoginHandler}.
 */
public class JWTLoginTest {

  private UserReferenceProvider userReferenceProvider;
  private JWTGenerator generator;
  private JWTRequestHeaderAuthenticationFilter authFilter;
  private DynamicLoginHandler loginHandler;
  private UserDetailsService userDetailsServiceExistingUser;
  private UserDetailsService userDetailsServiceNewUser;
  private GuavaCachedUrlJwkProvider validJwkProvider;
  private GuavaCachedUrlJwkProvider invalidJwkProvider;

  @Before
  public void setUp() throws NoSuchAlgorithmException, JwkException {
    generator = JWTGenerator.getInstance();

    // Prepare login handler
    loginHandler = new DynamicLoginHandler();
    loginHandler.setUserDirectoryService(createNiceMock(UserDirectoryService.class));
    loginHandler.setUsernameMapping(generator.getUsernameMapping());
    loginHandler.setNameMapping(generator.getNameMapping());
    loginHandler.setEmailMapping(generator.getEmailMapping());
    loginHandler.setRoleMappings(generator.getRolesMappings());
    loginHandler.setExpectedAlgorithms(List.of(generator.getSymmetricAlgorithm().getName()));
    loginHandler.setSecret(generator.getSecret());
    loginHandler.setClaimConstraints(generator.generateValidClaimConstraints());

    SecurityService securityService = createNiceMock(SecurityService.class);
    expect(securityService.getOrganization())
        .andReturn(new DefaultOrganization())
        .atLeastOnce();
    replay(securityService);
    loginHandler.setSecurityService(securityService);

    userReferenceProvider = createNiceMock(UserReferenceProvider.class);
    expect(userReferenceProvider.findUserReference(anyString(), anyString()))
        .andReturn(createNiceMock(JpaUserReference.class))
        .atLeastOnce();
    replay(userReferenceProvider);
    loginHandler.setUserReferenceProvider(userReferenceProvider);

    // Prepare authentication filter
    authFilter = new JWTRequestHeaderAuthenticationFilter();
    authFilter.setLoginHandler(loginHandler);
    authFilter.setPrincipalRequestHeader("Authorization");

    // Prepare user details service variants
    userDetailsServiceExistingUser = createNiceMock(UserDetailsService.class);
    expect(userDetailsServiceExistingUser.loadUserByUsername(anyString()))
        .andReturn(createNiceMock(UserDetails.class))
        .atLeastOnce();
    replay(userDetailsServiceExistingUser);

    userDetailsServiceNewUser = createNiceMock(UserDetailsService.class);
    expect(userDetailsServiceNewUser.loadUserByUsername(anyString()))
        .andThrow(new UsernameNotFoundException(""))
        .atLeastOnce();
    replay(userDetailsServiceNewUser);

    // Set default user details service
    loginHandler.setUserDetailsService(userDetailsServiceExistingUser);
    loginHandler.afterPropertiesSet();

    // Prepare JWK provider variants
    validJwkProvider = createMock(GuavaCachedUrlJwkProvider.class);
    expect(validJwkProvider.getAlgorithms(anyObject(), anyBoolean()))
        .andReturn(List.of(Algorithm.RSA512(generator.getPublicKey(), null)))
        .atLeastOnce();
    replay(validJwkProvider);

    invalidJwkProvider = createMock(GuavaCachedUrlJwkProvider.class);
    expect(invalidJwkProvider.getAlgorithms(anyObject(), anyBoolean()))
        .andReturn(List.of(Algorithm.RSA512(generator.getInvalidPublicKey(), null)))
        .atLeastOnce();
    replay(invalidJwkProvider);
  }

  private HttpServletRequest mockRequest(String content) {
    HttpServletRequest request = createNiceMock(HttpServletRequest.class);
    expect(request.getHeader("Authorization"))
        .andReturn(content)
        .atLeastOnce();
    replay(request);
    return request;
  }

  @Test
  public void testLoginExistingUser() {
    loginHandler.setUserDetailsService(userDetailsServiceExistingUser);
    Object username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(generator.generateValidSymmetricJWT())
    );
    assertEquals(username, generator.getUsername());
  }

  @Test
  public void testLoginNewUser() {
    loginHandler.setUserDetailsService(userDetailsServiceNewUser);
    Object username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(generator.generateValidSymmetricJWT())
    );
    assertEquals(username, generator.getUsername());
  }

  @Test
  public void testLoginWithCache() {
    Object username;

    username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(generator.generateValidSymmetricJWT())
    );
    assertEquals(username, generator.getUsername());

    // Make sure that the cache is used and no calls to the mocked user reference provider are made
    reset(userReferenceProvider);

    username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(generator.generateValidSymmetricJWT())
    );
    assertEquals(username, generator.getUsername());
  }

  @Test
  public void testExpiredLoginWithCache() throws InterruptedException {
    Object username;
    String expiringJwt = generator.generateValidSymmetricJWT(1000);

    username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(expiringJwt)
    );
    assertEquals(username, generator.getUsername());

    // Wait until JWT expires
    TimeUnit.SECONDS.sleep(1);

    // Make sure that the cache is used and no calls to the mocked user reference provider are made
    reset(userReferenceProvider);

    username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(expiringJwt)
    );
    assertNull(username);
  }

  @Test
  public void testLoginWithPrincipalPrefix() {
    Object username;
    String prefix = "Bearer ";
    authFilter.setPrincipalPrefix(prefix);

    // Existing principal prefix
    loginHandler.setUserDetailsService(userDetailsServiceNewUser);
    username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(prefix + generator.generateValidSymmetricJWT())
    );
    assertEquals(username, generator.getUsername());

    // Missing principal prefix
    username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(generator.generateValidSymmetricJWT())
    );
    assertNull(username);
  }

  @Test
  public void testLoginWithInvalidAlgorithm() {
    loginHandler.setExpectedAlgorithms(List.of("XY"));
    Object username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(generator.generateValidSymmetricJWT())
    );
    assertNull(username);
  }

  @Test
  public void testLoginWithInvalidClaimConstraints() {
    loginHandler.setClaimConstraints(generator.generateInvalidClaimConstraints());
    Object username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(generator.generateValidSymmetricJWT())
    );
    assertNull(username);
  }

  @Test
  public void testLoginWithInvalidJWT() {
    Object username;

    // Expired JWT
    username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(generator.generateExpiredSymmetricJWT())
    );
    assertNull(username);

    // Not JWT at all
    username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest("XY")
    );
    assertNull(username);
  }

  @Test
  public void testLoginWithInvalidMapping() {
    loginHandler.setRoleMappings(List.of("null"));
    Object username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(generator.generateExpiredSymmetricJWT())
    );
    assertNull(username);
  }

  @Test
  public void testLoginWithInvalidRequestHeader() {
    authFilter.setPrincipalRequestHeader("XY");
    Exception exception = assertThrows(
        PreAuthenticatedCredentialsNotFoundException.class,
        () -> authFilter.getPreAuthenticatedPrincipal(
            mockRequest(generator.generateExpiredSymmetricJWT())
        )
    );
    assertTrue(exception.getMessage().startsWith("XY header not found in request"));
  }

  @Test
  public void testLoginWithValidJwks() throws IllegalAccessException {
    loginHandler.setJwksUrl("https://auth.example.org/.well-known/jwks.json");
    loginHandler.setExpectedAlgorithms(List.of(generator.getAsymmetricAlgorithm().getName()));
    loginHandler.setSecret(null);

    // Inject mocked JWK provider
    FieldUtils.writeField(loginHandler, "jwkProvider", validJwkProvider, true);
    Object username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(generator.generateValidAsymmetricJWT())
    );
    assertEquals(username, generator.getUsername());
  }

  @Test
  public void testLoginWithInvalidJwks() throws IllegalAccessException {
    loginHandler.setJwksUrl("https://auth.example.org/.well-known/jwks.json");
    loginHandler.setExpectedAlgorithms(List.of(generator.getAsymmetricAlgorithm().getName()));
    loginHandler.setSecret(null);

    // Inject mocked JWK provider
    FieldUtils.writeField(loginHandler, "jwkProvider", invalidJwkProvider, true);
    Object username = authFilter.getPreAuthenticatedPrincipal(
        mockRequest(generator.generateValidAsymmetricJWT())
    );
    assertNull(username);
  }

}
