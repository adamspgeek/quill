package me.vickychijwani.spectre.network;

import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.github.slugify.Slugify;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Random;

import io.reactivex.Observable;
import io.realm.RealmList;
import me.vickychijwani.spectre.model.entity.AuthToken;
import me.vickychijwani.spectre.model.entity.ConfigurationParam;
import me.vickychijwani.spectre.model.entity.Post;
import me.vickychijwani.spectre.model.entity.Role;
import me.vickychijwani.spectre.model.entity.Setting;
import me.vickychijwani.spectre.model.entity.User;
import me.vickychijwani.spectre.network.entity.ApiErrorList;
import me.vickychijwani.spectre.network.entity.AuthReqBody;
import me.vickychijwani.spectre.network.entity.ConfigurationList;
import me.vickychijwani.spectre.network.entity.PostList;
import me.vickychijwani.spectre.network.entity.PostStubList;
import me.vickychijwani.spectre.network.entity.RefreshReqBody;
import me.vickychijwani.spectre.network.entity.RevokeReqBody;
import me.vickychijwani.spectre.network.entity.SettingsList;
import me.vickychijwani.spectre.network.entity.UserList;
import me.vickychijwani.spectre.util.functions.Action1;
import me.vickychijwani.spectre.util.functions.Action3;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.HttpException;
import retrofit2.Response;
import retrofit2.Retrofit;

import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.isEmptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

/**
 * TYPE: integration-style (requires running Ghost server)
 * PURPOSE: contract tests for the latest version of the Ghost API that we support
 *
 * Run these against a live Ghost instance to detect ANY behaviour changes (breaking or
 * non-breaking) in the API when a new Ghost version comes out.
 *
 * What's NOT tested here:
 * - Ghost Auth (needs a UI)
 */

public final class GhostApiTest {

    private static final String BLOG_URL = "http://localhost:2368/";
    private static final String TEST_USER = "user@example.com";
    private static final String TEST_PWD = "ghosttest";

    private static GhostApiService API;
    private static Slugify SLUGIFY;
    private static Retrofit RETROFIT;

    @BeforeClass
    public static void setupApiService() {
        OkHttpClient httpClient = new ProductionHttpClientFactory().create(null)
                .newBuilder()
                .addInterceptor(new HttpLoggingInterceptor()
                        .setLevel(HttpLoggingInterceptor.Level.BODY))
                .build();
        RETROFIT = GhostApiUtils.getRetrofit(BLOG_URL, httpClient);
        API = RETROFIT.create(GhostApiService.class);

        // delete the default "Welcome to Ghost" post, if it exists
        doWithAuthToken(token -> {
            PostList posts = execute(API.getPosts(token.getAuthHeader(), "", 100)).body();
            if (posts.posts.size() > 1) {
                throw new IllegalStateException("Not deleting existing posts since there are more than 1!");
            }
            for (Post post : posts.posts) {
                execute(API.deletePost(token.getAuthHeader(), post.getId()));
            }
        });
    }

    @BeforeClass
    public static void setupSlugify() throws IOException {
        SLUGIFY = new Slugify();
    }

    @Test
    public void test_getClientSecret() {
        String clientSecret = getClientSecret();

        // must NOT be null since that's only possible with a very old Ghost version (< 0.7.x)
        assertThat(clientSecret, notNullValue());
        // Ghost uses a 12-character client secret, evident from the Ghost source code (1 byte can hold 2 hex chars):
        // { secret: crypto.randomBytes(6).toString('hex') }
        // file: core/server/data/migration/fixtures/004/04-update-ghost-admin-client.js
        assertThat(clientSecret.length(), is(12));
    }

    @Test
    public void test_getAuthToken_withPassword() {
        doWithAuthToken(token -> {
            assertThat(token.getTokenType(), is("Bearer"));
            assertThat(token.getAccessToken(), notNullValue());
            assertThat(token.getRefreshToken(), notNullValue());
            assertThat(token.getExpiresIn(), is(2628000));
        });
    }

    @Test
    public void test_getAuthToken_wrongEmail() {
        String clientSecret = getClientSecret();
        AuthReqBody credentials = AuthReqBody.fromPassword(clientSecret, "wrong@email.com", TEST_PWD);
        try {
            execute(API.getAuthToken(credentials));
            // fail the test if no exception is thrown
            assertThat("Test did not throw exception as expected!", false, is(true));
        } catch (Exception e) {
            assertThat(e.getCause(), instanceOf(HttpException.class));
            HttpException httpEx = (HttpException) e.getCause();
            ApiErrorList apiErrors = GhostApiUtils.parseApiErrors(RETROFIT, httpEx);
            assertThat(apiErrors, notNullValue());
            assertThat(apiErrors.errors.size(), is(1));
            assertThat(apiErrors.errors.get(0).errorType, is("NotFoundError"));
            assertThat(apiErrors.errors.get(0).message, notNullValue());
            assertThat(apiErrors.errors.get(0).message, not(""));
        }
    }

    @Test
    public void test_getAuthToken_wrongPassword() {
        String clientSecret = getClientSecret();
        AuthReqBody credentials = AuthReqBody.fromPassword(clientSecret, TEST_USER, "wrongpassword");
        try {
            execute(API.getAuthToken(credentials));
            // fail the test if no exception is thrown
            assertThat("Test did not throw exception as expected!", false, is(true));
        } catch (Exception e) {
            assertThat(e.getCause(), instanceOf(HttpException.class));
            HttpException httpEx = (HttpException) e.getCause();
            ApiErrorList apiErrors = GhostApiUtils.parseApiErrors(RETROFIT, httpEx);
            assertThat(apiErrors, notNullValue());
            assertThat(apiErrors.errors.size(), is(1));
            assertThat(apiErrors.errors.get(0).errorType, is("UnauthorizedError"));
            assertThat(apiErrors.errors.get(0).message, notNullValue());
            assertThat(apiErrors.errors.get(0).message, not(""));
        }
    }

    @Test
    public void test_getAuthToken_withRefreshToken() {
        doWithAuthToken(expiredToken -> {
            String clientSecret = getClientSecret();
            RefreshReqBody credentials = new RefreshReqBody(expiredToken.getRefreshToken(),
                    clientSecret);
            Response<AuthToken> response = execute(API.refreshAuthToken(credentials));
            AuthToken refreshedToken = response.body();

            assertThat(response.code(), is(HTTP_OK));
            assertThat(refreshedToken.getTokenType(), is("Bearer"));
            assertThat(refreshedToken.getAccessToken(), notNullValue());
            assertThat(refreshedToken.getRefreshToken(), isEmptyOrNullString());
            assertThat(refreshedToken.getExpiresIn(), is(2628000));

            RevokeReqBody[] revokeReqs = new RevokeReqBody[] {
                    new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_REFRESH, refreshedToken.getRefreshToken(), clientSecret),
                    new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_ACCESS, refreshedToken.getAccessToken(), clientSecret)
            };
            for (RevokeReqBody reqBody : revokeReqs) {
                execute(API.revokeAuthToken(refreshedToken.getAuthHeader(), reqBody));
            }
        });
    }

    @Test
    public void test_revokeAuthToken() {
        String clientSecret = getClientSecret();
        AuthReqBody credentials = AuthReqBody.fromPassword(clientSecret, TEST_USER, TEST_PWD);
        AuthToken token = execute(API.getAuthToken(credentials));

        RevokeReqBody[] revokeReqs = new RevokeReqBody[] {
                new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_REFRESH, token.getRefreshToken(), clientSecret),
                new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_ACCESS, token.getAccessToken(), clientSecret)
        };
        for (RevokeReqBody reqBody : revokeReqs) {
            Response<JsonElement> response = execute(API.revokeAuthToken(token.getAuthHeader(), reqBody));
            JsonElement jsonResponse = response.body();
            JsonObject jsonObj = jsonResponse.getAsJsonObject();

            assertThat(response.code(), is(HTTP_OK));
            assertThat(jsonObj.has("error"), is(false));
            assertThat(jsonObj.get("token").getAsString(), is(reqBody.token));
        }
    }

    @Test
    public void test_getCurrentUser() {
        doWithAuthToken(token -> {
            Response<UserList> response = execute(API.getCurrentUser(token.getAuthHeader(), ""));
            UserList users = response.body();
            User user = users.users.get(0);

            assertThat(response.code(), is(HTTP_OK));
            assertThat(response.headers().get("ETag"), not(isEmptyOrNullString()));
            assertThat(user, notNullValue());
            //assertThat(user.getId(), instanceOf(Integer.class)); // no-op, int can't be null
            assertThat(user.getName(), notNullValue());
            assertThat(user.getSlug(), notNullValue());
            assertThat(user.getEmail(), is(TEST_USER));
            //assertThat(user.getImage(), anyOf(nullValue(), notNullValue())); // no-op
            //assertThat(user.getBio(), anyOf(nullValue(), notNullValue())); // no-op
            assertThat(user.getRoles(), not(empty()));

            Role role = user.getRoles().first();
            //assertThat(role.getId(), instanceOf(Integer.class)); // no-op, int can't be null
            assertThat(role.getName(), notNullValue());
            assertThat(role.getDescription(), notNullValue());
        });
    }

    @Test
    public void test_createPost() {
        doWithAuthToken(token -> {
            createRandomPost(token, (expectedPost, response, createdPost) -> {
                assertThat(response.code(), is(HTTP_CREATED));
                assertThat(createdPost.getTitle(), is(expectedPost.getTitle()));
                assertThat(createdPost.getSlug(), is(SLUGIFY.slugify(expectedPost.getTitle())));
                assertThat(createdPost.getStatus(), is(expectedPost.getStatus()));
                assertThat(createdPost.getMarkdown(), is(expectedPost.getMarkdown()));
                assertThat(createdPost.getHtml(), is("<p>" + expectedPost.getMarkdown() + "</p>"));
                assertThat(createdPost.getTags(), is(expectedPost.getTags()));
                assertThat(createdPost.isFeatured(), is(false));
            });
        });
    }

    @Test
    public void test_getPosts() {
        Action3<AuthToken, Post, Post> checkPosts = (token, p1, p2) -> {
            Response<PostList> response = execute(API.getPosts(token.getAuthHeader(), "", 100));
            List<Post> posts = response.body().posts;
            assertThat(response.code(), is(HTTP_OK));
            assertThat(posts.size(), is(2));
            // posts are returned in reverse-chrono order
            // check latest post
            assertThat(posts.get(0).getTitle(), is(p2.getTitle()));
            assertThat(posts.get(0).getMarkdown(), is(p2.getMarkdown()));
            // check second-last post
            assertThat(posts.get(1).getTitle(), is(p1.getTitle()));
            assertThat(posts.get(1).getMarkdown(), is(p1.getMarkdown()));
        };
        doWithAuthToken(token -> {
            createRandomPost(token, (post1, r1, cp1) -> {
                createRandomPost(token, (post2, r2, cp2) -> {
                    checkPosts.call(token, post1, post2);
                });
            });
        });
    }

    @Test
    public void test_getPosts_limit() {
        // setting the limit to N should return the *latest* N posts
        Action3<AuthToken, Post, Post> checkPosts = (token, p1, p2) -> {
            Response<PostList> response = execute(API.getPosts(token.getAuthHeader(), "", 1));
            List<Post> posts = response.body().posts;
            assertThat(response.code(), is(HTTP_OK));
            assertThat(posts.size(), is(1));
            assertThat(posts.get(0).getTitle(), is(p2.getTitle()));
            assertThat(posts.get(0).getMarkdown(), is(p2.getMarkdown()));
        };
        doWithAuthToken(token -> {
            createRandomPost(token, (post1, r1, cp1) -> {
                createRandomPost(token, (post2, r2, cp2) -> {
                    checkPosts.call(token, post1, post2);
                });
            });
        });
    }

    @Test
    public void test_getPost() {
        doWithAuthToken(token -> {
            createRandomPost(token, (expected, ___, created) -> {
                Response<PostList> response = execute(API.getPost(token.getAuthHeader(), created.getId()));
                Post post = response.body().posts.get(0);
                assertThat(response.code(), is(HTTP_OK));
                assertThat(post.getTitle(), is(expected.getTitle()));
                assertThat(post.getMarkdown(), is(expected.getMarkdown()));
            });
        });
    }

    @Test
    public void test_deletePost() {
        doWithAuthToken(token -> {
            final Post[] deleted = {null};
            createRandomPost(token, (expected, ___, created) -> {
                deleted[0] = created;
            });
            // post should be deleted by this point
            Response<PostList> response = execute(API.getPost(token.getAuthHeader(), deleted[0].getId()));
            assertThat(response.code(), is(HTTP_NOT_FOUND));
        });
    }

    @Test
    public void test_getSettings() {
        doWithAuthToken(token -> {
            Response<SettingsList> response = execute(API.getSettings(token.getAuthHeader(), ""));
            List<Setting> settings = response.body().settings;

            assertThat(response.code(), is(HTTP_OK));
            assertThat(response.headers().get("ETag"), not(isEmptyOrNullString()));
            assertThat(settings, notNullValue());
            // blog title
            assertThat(settings, hasItem(allOf(
                    hasProperty("key", is("title")),
                    hasProperty("value", not(isEmptyOrNullString())))));
            // permalink format
            assertThat(settings, hasItem(allOf(
                    hasProperty("key", is("permalinks")),
                    hasProperty("value", is("/:slug/")))));
        });
    }

    @Test
    public void test_getConfiguration() {
        doWithAuthToken(token -> {
            Response<ConfigurationList> response = execute(API.getConfiguration());
            List<ConfigurationParam> config = response.body().configuration;

            assertThat(response.code(), is(HTTP_OK));
            assertThat(response.headers().get("ETag"), not(isEmptyOrNullString()));
            assertThat(config, notNullValue());
            // is file storage enabled? if not, images etc can't be uploaded
            assertThat(config, hasItem(allOf(
                    hasProperty("key", is("fileStorage")),
                    hasProperty("value", anyOf(is("true"), is("false"))))));
        });
    }

    @Test
    public void test_getConfigAbout() {
        doWithAuthToken(token -> {
            Response<JsonObject> response = execute(API.getVersion(token.getAuthHeader()));
            JsonObject about = response.body();
            String version = null;
            version = about
                    .get("configuration").getAsJsonArray()
                    .get(0).getAsJsonObject()
                    .get("version").getAsString();

            assertThat(response.code(), is(HTTP_OK));
            assertThat(response.headers().get("ETag"), not(isEmptyOrNullString()));
            assertThat(about, notNullValue());
            assertThat(version, not(isEmptyOrNullString()));
        });
    }



    // private helpers
    private static void doWithAuthToken(Action1<AuthToken> callback) {
        String clientSecret = getClientSecret();
        AuthReqBody credentials = AuthReqBody.fromPassword(clientSecret, TEST_USER, TEST_PWD);
        AuthToken token = execute(API.getAuthToken(credentials));
        try {
            callback.call(token);
        } finally {
            RevokeReqBody[] revokeReqs = new RevokeReqBody[] {
                    new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_REFRESH, token.getRefreshToken(), clientSecret),
                    new RevokeReqBody(RevokeReqBody.TOKEN_TYPE_ACCESS, token.getAccessToken(), clientSecret)
            };
            for (RevokeReqBody reqBody : revokeReqs) {
                execute(API.revokeAuthToken(token.getAuthHeader(), reqBody));
            }
        }
    }

    private static void createRandomPost(AuthToken token,
                                         Action3<Post, Response<PostList>, Post> callback) {
        String title = getRandomString(20);
        String markdown = getRandomString(100);
        Post newPost = new Post();
        newPost.setTitle(title);
        newPost.setMarkdown(markdown);
        newPost.setTags(new RealmList<>());
        Response<PostList> response = execute(API.createPost(token.getAuthHeader(),
                PostStubList.from(newPost)));
        Post created = response.body().posts.get(0);

        try {
            callback.call(newPost, response, created);
        } finally {
            execute(API.deletePost(token.getAuthHeader(), created.getId()));
        }
    }

    @Nullable
    private static String getClientSecret() {
        ConfigurationList config = execute(API.getConfiguration()).body();
        for (ConfigurationParam param : config.configuration) {
            if ("clientSecret".equals(param.getKey())) {
                return param.getValue();
            }
        }
        throw new NullPointerException("Client secret is null!");
    }

    @NonNull
    private static <T> Response<T> execute(Call<T> call) {
        // intentionally swallows the IOException to make test code cleaner
        try {
            return call.execute();
        } catch (IOException e) {
            e.printStackTrace();
            // suppress leaking knowledge of this null to hide false-positive errors by IntelliJ
            //noinspection ConstantConditions
            return null;
        }
    }

    private static <T> T execute(Observable<T> observable) {
        return observable.blockingFirst();
    }

    private static String getRandomString(int length) {
        Random random = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; ++i) {
            char c = (char)(random.nextInt('z'-'a')+'a');
            sb.append(c);
        }
        return sb.toString();
    }

}
