/*
  Copyright 2012 - 2015 pac4j organization

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
package org.pac4j.oauth.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.pac4j.core.context.WebContext;
import org.pac4j.core.exception.HttpCommunicationException;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.util.CommonHelper;
import org.pac4j.oauth.exception.OAuthCredentialsException;
import org.pac4j.oauth.profile.JsonHelper;
import org.pac4j.oauth.profile.facebook.FacebookAttributesDefinition;
import org.pac4j.oauth.profile.facebook.FacebookProfile;
import org.scribe.builder.api.ExtendedFacebookApi;
import org.scribe.builder.api.StateApi20;
import org.scribe.model.OAuthConfig;
import org.scribe.model.OAuthConstants;
import org.scribe.model.ProxyOAuthRequest;
import org.scribe.model.Response;
import org.scribe.model.SignatureType;
import org.scribe.model.Token;
import org.scribe.oauth.StateOAuth20ServiceImpl;

/**
 * <p>This class is the OAuth client to authenticate users in Facebook.</p>
 * <p>By default, the following <i>scope</i> is requested to Facebook : user_likes, user_about_me, user_birthday, user_education_history,
 * email, user_hometown, user_relationship_details, user_location, user_religion_politics, user_relationships, user_website and
 * user_work_history.</p>
 * <p>The <i>scope</i> can be defined to require permissions from the user and retrieve fields from Facebook, by using the
 * {@link #setScope(String)} method.</p>
 * <p>By default, the following <i>fields</i> are requested to Facebook : id, name, first_name, middle_name, last_name, gender, locale,
 * languages, link, third_party_id, timezone, updated_time, verified, bio, birthday, education, email, hometown, interested_in,
 * location, political, favorite_athletes, favorite_teams, quotes, relationship_status, religion, significant_other, website and work.</p>
 * <p>The <i>fields</i> can be defined and requested to Facebook, by using the {@link #setFields(String)} method.</p>
 * <p>The number of results can be limited by using the {@link #setLimit(int)} method.</p>
 * <p>An extended access token can be requested by setting <code>true</code> on the {@link #setRequiresExtendedToken(boolean)} method.</p>
 * <p>It returns a {@link org.pac4j.oauth.profile.facebook.FacebookProfile}.</p>
 * <p>More information at http://developers.facebook.com/docs/reference/api/user/</p>
 * <p>More information at https://developers.facebook.com/docs/howtos/login/extending-tokens/</p>
 * 
 * @author Jerome Leleu
 * @author Mehdi BEN HAJ ABBES
 * @since 1.0.0
 */
public class FacebookClient extends BaseOAuth20StateClient<FacebookProfile> {
    
    private static final String EXCHANGE_TOKEN_URL = "https://graph.facebook.com/v2.4/oauth/access_token?grant_type=fb_exchange_token";
    
    private static final String EXCHANGE_TOKEN_PARAMETER = "fb_exchange_token";

    private static final String APPSECRET_PARAMETER = "appsecret_proof";
    
    public final static String DEFAULT_FIELDS = "id,name,first_name,middle_name,last_name,gender,locale,languages,link,third_party_id,timezone,updated_time,verified,bio,birthday,education,email,hometown,interested_in,location,political,favorite_athletes,favorite_teams,quotes,relationship_status,religion,significant_other,website,work";
    
    protected String fields = DEFAULT_FIELDS;
    
    protected final static String BASE_URL = "https://graph.facebook.com/v2.4/me";
    
    public final static String DEFAULT_SCOPE = "user_likes,user_about_me,user_birthday,user_education_history,email,user_hometown,user_relationship_details,user_location,user_religion_politics,user_relationships,user_website,user_work_history";
    
    protected String scope = DEFAULT_SCOPE;
    
    public final static int DEFAULT_LIMIT = 0;
    
    protected int limit = DEFAULT_LIMIT;
    
    protected boolean requiresExtendedToken = false;
    
    protected StateApi20 api20;

    protected boolean useAppsecretProof = false;
    
    public FacebookClient() {
    }
    
    public FacebookClient(final String key, final String secret) {
        setKey(key);
        setSecret(secret);
    }
    
    @Override
    protected void internalInit(final WebContext context) {
        super.internalInit(context);
        CommonHelper.assertNotBlank("fields", this.fields);
        this.api20 = new ExtendedFacebookApi();
        if (StringUtils.isNotBlank(this.scope)) {
            this.service = new StateOAuth20ServiceImpl(this.api20, new OAuthConfig(this.key, this.secret,
                                                                                   computeFinalCallbackUrl(context),
                                                                                   SignatureType.Header, this.scope,
                                                                                   null), this.connectTimeout,
                                                       this.readTimeout, this.proxyHost, this.proxyPort);
        } else {
            this.service = new StateOAuth20ServiceImpl(this.api20, new OAuthConfig(this.key, this.secret,
                                                                                   computeFinalCallbackUrl(context),
                                                                                   SignatureType.Header, null, null),
                                                       this.connectTimeout, this.readTimeout, this.proxyHost,
                                                       this.proxyPort);
        }
    }
    
    @Override
    protected String getProfileUrl(final Token accessToken) {
        String url = BASE_URL + "?fields=" + this.fields;
        if (this.limit > DEFAULT_LIMIT) {
            url += "&limit=" + this.limit;
        }
        // possibly include the appsecret_proof parameter
        if (this.useAppsecretProof) {
            url = computeAppSecretProof(url, accessToken);
        }
        return url;
    }
    
    @Override
    protected FacebookProfile retrieveUserProfileFromToken(final Token accessToken) {
        String body = sendRequestForData(accessToken, getProfileUrl(accessToken));
        if (body == null) {
            throw new HttpCommunicationException("Not data found for accessToken : " + accessToken);
        }
        final FacebookProfile profile = extractUserProfile(body);
        addAccessTokenToProfile(profile, accessToken);
        if (profile != null && this.requiresExtendedToken) {
            String url = CommonHelper.addParameter(EXCHANGE_TOKEN_URL, OAuthConstants.CLIENT_ID, this.key);
            url = CommonHelper.addParameter(url, OAuthConstants.CLIENT_SECRET, this.secret);
            url = addExchangeToken(url, accessToken);
            final ProxyOAuthRequest request = createProxyRequest(url);
            final long t0 = System.currentTimeMillis();
            final Response response = request.send();
            final int code = response.getCode();
            body = response.getBody();
            final long t1 = System.currentTimeMillis();
            logger.debug("Request took : " + (t1 - t0) + " ms for : " + url);
            logger.debug("response code : {} / response body : {}", code, body);
            if (code == 200) {
                logger.debug("Retrieve extended token from : {}", body);
                final Token extendedAccessToken = this.api20.getAccessTokenExtractor().extract(body);
                logger.debug("Extended token : {}", extendedAccessToken);
                addAccessTokenToProfile(profile, extendedAccessToken);
            } else {
                logger.error("Cannot get extended token : {} / {}", code, body);
            }
        }
        return profile;
    }
    
    @Override
    protected FacebookProfile extractUserProfile(final String body) {
        final FacebookProfile profile = new FacebookProfile();
        final JsonNode json = JsonHelper.getFirstNode(body);
        if (json != null) {
            profile.setId(JsonHelper.getElement(json, "id"));
            for (final String attribute : profile.getAttributesDefinition().getPrimaryAttributes()) {
                profile.addAttribute(attribute, JsonHelper.getElement(json, attribute));
            }
            extractData(profile, json, FacebookAttributesDefinition.FRIENDS);
            extractData(profile, json, FacebookAttributesDefinition.MOVIES);
            extractData(profile, json, FacebookAttributesDefinition.MUSIC);
            extractData(profile, json, FacebookAttributesDefinition.BOOKS);
            extractData(profile, json, FacebookAttributesDefinition.LIKES);
            extractData(profile, json, FacebookAttributesDefinition.ALBUMS);
            extractData(profile, json, FacebookAttributesDefinition.EVENTS);
            extractData(profile, json, FacebookAttributesDefinition.GROUPS);
            extractData(profile, json, FacebookAttributesDefinition.MUSIC_LISTENS);
            extractData(profile, json, FacebookAttributesDefinition.PICTURE);
        }
        return profile;
    }
    
    protected void extractData(final FacebookProfile profile, final JsonNode json, final String name) {
        final JsonNode data = (JsonNode) JsonHelper.getElement(json, name);
        if (data != null) {
            profile.addAttribute(name, JsonHelper.getElement(data, "data"));
        }
    }
    
    @Override
    protected boolean hasBeenCancelled(final WebContext context) {
        final String error = context.getRequestParameter(OAuthCredentialsException.ERROR);
        final String errorReason = context.getRequestParameter(OAuthCredentialsException.ERROR_REASON);
        // user has denied permissions
        if ("access_denied".equals(error) && "user_denied".equals(errorReason)) {
            return true;
        } else {
            return false;
        }
    }

    public void setUseAppSecretProof(boolean useSecret) {
        this.useAppsecretProof = useSecret;
    }

    public boolean getUseAppSecretProof() {
        return this.useAppsecretProof;
    }

    /**
     * The code in this method is based on this blog post: https://www.sammyk.me/the-single-most-important-way-to-make-your-facebook-app-more-secure
     * and this answer: https://stackoverflow.com/questions/7124735/hmac-sha256-algorithm-for-signature-calculation
     *
     * @param url the URL to which we're adding the proof
     * @param token the application token we pass back and forth
     * @return URL with the appsecret_proof parameter added
     */
    protected String computeAppSecretProof(String url, Token token) {
        try {
            Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secret_key = new SecretKeySpec(this.secret.getBytes("UTF-8"), "HmacSHA256");
            sha256_HMAC.init(secret_key);
            String proof = org.apache.commons.codec.binary.Hex.encodeHexString(sha256_HMAC.doFinal(token.getToken().getBytes("UTF-8")));
            url = CommonHelper.addParameter(url, APPSECRET_PARAMETER, proof);
            return url;
        } catch (Exception e) {
            throw new TechnicalException("Unable to compute appsecret_proof", e);
        }
    }

    /**
     * Adds the token to the URL in question. If we require appsecret_proof, then this method
     * will also add the appsecret_proof parameter to the URL, as Facebook expects.
     *
     * @param url the URL to modify
     * @param accessToken the token we're passing back and forth
     * @return url with additional parameter(s)
     */
    protected String addExchangeToken(String url, Token accessToken) {
        if (this.useAppsecretProof) {
            url = computeAppSecretProof(url, accessToken);
        }
        return CommonHelper.addParameter(url, EXCHANGE_TOKEN_PARAMETER, accessToken.getToken());
    }
    
    public String getScope() {
        return this.scope;
    }
    
    public void setScope(final String scope) {
        this.scope = scope;
    }
    
    public String getFields() {
        return this.fields;
    }
    
    public void setFields(final String fields) {
        this.fields = fields;
    }
    
    public int getLimit() {
        return this.limit;
    }
    
    public void setLimit(final int limit) {
        this.limit = limit;
    }
    
    public boolean isRequiresExtendedToken() {
        return this.requiresExtendedToken;
    }
    
    public void setRequiresExtendedToken(final boolean requiresExtendedToken) {
        this.requiresExtendedToken = requiresExtendedToken;
    }
}
