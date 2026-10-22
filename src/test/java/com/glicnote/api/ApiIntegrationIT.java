package com.glicnote.api;

import com.fasterxml.jackson.databind.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationIT {
    static final UUID ALICE=UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID BOB=UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID CAROL=UUID.fromString("33333333-3333-4333-8333-333333333333");
    static final RSAKey KEY;
    static final ECKey EC_KEY;
    static final HttpServer JWKS;
    static final String ISSUER;
    static final JwtEncoder ENCODER;
    static {
        try {
            KEY=new RSAKeyGenerator(2048).keyID("integration-key").generate();
            EC_KEY=new ECKeyGenerator(Curve.P_256).keyID("integration-ec-key").generate();
            JWKS=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            JWKS.createContext("/jwks",exchange -> {
                byte[] body=new JWKSet(List.of(KEY.toPublicJWK(),EC_KEY.toPublicJWK())).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,body.length);
                try(var stream=exchange.getResponseBody()) { stream.write(body); }
            });
            JWKS.start();
            ISSUER="http://127.0.0.1:"+JWKS.getAddress().getPort()+"/auth/v1";
            ENCODER=new NimbusJwtEncoder(new ImmutableJWKSet<SecurityContext>(new JWKSet(List.of(KEY,EC_KEY))));
        } catch(Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",() -> Objects.requireNonNull(System.getenv("TEST_DB_URL"),"Run scripts/test.sh"));
        registry.add("spring.datasource.username",() -> System.getenv("TEST_DB_USERNAME"));
        registry.add("spring.datasource.password",() -> System.getenv("TEST_DB_PASSWORD"));
        registry.add("app.auth.issuer",() -> ISSUER);
        registry.add("app.auth.jwks-uri",() -> "http://127.0.0.1:"+JWKS.getAddress().getPort()+"/jwks");
    }
    @TestConfiguration static class TimeConfig {
        @Bean @Primary Clock testClock() { return Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"),ZoneOffset.UTC); }
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcClient db;
    @BeforeEach void clear() { db.sql("TRUNCATE profiles CASCADE").update(); }
    @AfterAll static void stopJwks() { JWKS.stop(0); }

    String token(UUID id) { return token(id.toString(),ISSUER,"authenticated","authenticated",false,Instant.now().plusSeconds(600)); }
    String token(String subject,String issuer,String audience,String role,boolean anonymous,Instant expires) {
        var claims=JwtClaimsSet.builder().issuer(issuer).subject(subject).audience(List.of(audience))
            .issuedAt(Instant.now().minusSeconds(600)).expiresAt(expires).claim("role",role).claim("is_anonymous",anonymous)
            .claim("email","private@example.com").claim("user_metadata",Map.of("full_name","Original name")).build();
        return ENCODER.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY.getKeyID()).build(),claims)).getTokenValue();
    }
    ResultActions call(HttpMethod method,String path,UUID user,Object body) throws Exception {
        var request=request(method,URI.create(path)).header("Authorization","Bearer "+token(user));
        if(body!=null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));
        return mvc.perform(request);
    }
    JsonNode result(ResultActions action,int status) throws Exception {
        var response=action.andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(status);
        return response.getContentAsByteArray().length==0?json.nullNode():json.readTree(response.getContentAsByteArray());
    }
    JsonNode profile(UUID user,String username) throws Exception {
        return result(call(HttpMethod.PUT,"/v1/me",user,Map.of("name","Original name","username",username,"timeZone","America/Sao_Paulo")),200);
    }
    Map<String,Object> readingBody(UUID id,int glucose,String measuredAt,String moment,String zone,BigDecimal insulin) {
        var body=new HashMap<String,Object>(); body.put("id",id);body.put("glucoseMgDl",glucose);body.put("measuredAt",measuredAt);
        body.put("moment",moment);body.put("timeZone",zone);body.put("insulinUnits",insulin);return body;
    }
    JsonNode reading(UUID user,String at,String moment) throws Exception {
        return result(call(HttpMethod.POST,"/v1/readings",user,readingBody(UUID.randomUUID(),110,at,moment,"America/Sao_Paulo",new BigDecimal("2.5"))),201);
    }
    UUID id(JsonNode node) { return UUID.fromString(node.get("id").asText()); }
    UUID follow(UUID requester,UUID target) throws Exception { return id(result(call(HttpMethod.POST,"/v1/follow-requests/"+target,requester,null),201)); }
    void accept(UUID target,UUID follow,int version) throws Exception { result(call(HttpMethod.PUT,"/v1/follow-requests/"+follow+"/decision",target,Map.of("accept",true,"version",version)),200); }
    JsonNode share(UUID user,String type,UUID reference) throws Exception { return result(call(HttpMethod.POST,"/v1/posts",user,Map.of("type",type,"referenceId",reference,"caption","Shared by choice")),201); }

    String metadataToken(UUID user,Map<String,Object> metadata) {
        var claims=JwtClaimsSet.builder().issuer(ISSUER).subject(user.toString()).audience(List.of("authenticated"))
            .issuedAt(Instant.now().minusSeconds(60)).expiresAt(Instant.now().plusSeconds(600))
            .claim("role","authenticated").claim("email","private@example.com").claim("user_metadata",metadata).build();
        return ENCODER.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).keyId(KEY.getKeyID()).build(),claims)).getTokenValue();
    }
    @Test void googlePhotoBackfillsExistingProfilesAndRefreshesWithoutOverwritingEditedName() throws Exception {
        profile(ALICE,"alice");
        var first=result(mvc.perform(get("/v1/me").header("Authorization","Bearer "+metadataToken(ALICE,
            Map.of("full_name","Google name","avatar_url","http://invalid.example/photo","picture","https://lh3.googleusercontent.com/first")))),200);
        assertThat(first.get("photoUrl").asText()).isEqualTo("https://lh3.googleusercontent.com/first");
        assertThat(first.get("name").asText()).isEqualTo("Original name");
        var updated=result(mvc.perform(get("/v1/me").header("Authorization","Bearer "+metadataToken(ALICE,
            Map.of("avatar_url","https://lh3.googleusercontent.com/second")))),200);
        assertThat(updated.get("photoUrl").asText()).isEqualTo("https://lh3.googleusercontent.com/second");
        var invalid=result(mvc.perform(get("/v1/me").header("Authorization","Bearer "+metadataToken(ALICE,
            Map.of("avatar_url","https://invalid host/image")))),200);
        assertThat(invalid.get("photoUrl").asText()).isEqualTo("https://lh3.googleusercontent.com/second");
    }
    @Test void privacyPageIsPublicButPrivateEndpointsStillRequireAuthentication() throws Exception {
        var response=mvc.perform(get("/privacy.html")).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith("text/html");
        assertThat(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("Tech Mow","Política de privacidade","preliminar");
        assertThat(mvc.perform(get("/v1/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
    @Test void signedTokensAreRequiredAndClaimsAreValidated() throws Exception {
        assertThat(mvc.perform(get("/health")).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(mvc.perform(get("/v1/me")).andReturn().getResponse().getStatus()).isEqualTo(401);
        var invalid=List.of(
            token(ALICE.toString(),"https://wrong.example/auth/v1","authenticated","authenticated",false,Instant.now().plusSeconds(60)),
            token(ALICE.toString(),ISSUER,"anon","authenticated",false,Instant.now().plusSeconds(60)),
            token(ALICE.toString(),ISSUER,"authenticated","service_role",false,Instant.now().plusSeconds(60)),
            token(ALICE.toString(),ISSUER,"authenticated","authenticated",true,Instant.now().plusSeconds(60)),
            token("invalid-subject",ISSUER,"authenticated","authenticated",false,Instant.now().plusSeconds(60)),
            token(ALICE.toString(),ISSUER,"authenticated","authenticated",false,Instant.now().minusSeconds(120)),
            "unsigned.payload.signature");
        for(String token:invalid) assertThat(mvc.perform(get("/v1/me").header("Authorization","Bearer "+token)).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(result(call(HttpMethod.GET,"/v1/me",ALICE,null),200).get("id").asText()).isEqualTo(ALICE.toString());
    }
    @Test void supabaseEcSignaturesWorkAndTokensWithoutExpiryAreRejected() throws Exception {
        var claims=JwtClaimsSet.builder().issuer(ISSUER).subject(ALICE.toString()).audience(List.of("authenticated"))
            .claim("role","authenticated").issuedAt(Instant.now().minusSeconds(1));
        var header=JwsHeader.with(SignatureAlgorithm.ES256).keyId(EC_KEY.getKeyID()).build();
        String missingExpiry=ENCODER.encode(JwtEncoderParameters.from(header,claims.build())).getTokenValue();
        assertThat(mvc.perform(get("/v1/me").header("Authorization","Bearer "+missingExpiry)).andReturn().getResponse().getStatus()).isEqualTo(401);
        String valid=ENCODER.encode(JwtEncoderParameters.from(header,claims.expiresAt(Instant.now().plusSeconds(600)).build())).getTokenValue();
        var response=mvc.perform(get("/v1/me").header("Authorization","Bearer "+valid)).andReturn().getResponse();
        assertThat(response.getStatus()).withFailMessage(response.getContentAsString()).isEqualTo(200);
    }
    @Test void profileIsPrivateAndBootstrapDoesNotOverwriteEdits() throws Exception {
        profile(ALICE,"alice");profile(BOB,"bob");
        var me=result(call(HttpMethod.GET,"/v1/me",ALICE,null),200);
        assertThat(me.get("name").asText()).isEqualTo("Original name");
        var publicProfile=result(call(HttpMethod.GET,"/v1/users/"+ALICE,BOB,null),200);
        assertThat(publicProfile.has("email")).isFalse();assertThat(publicProfile.has("birthdayDay")).isFalse();
        assertThat(result(call(HttpMethod.GET,"/v1/users?query=ali",BOB,null),200).size()).isEqualTo(1);
        result(call(HttpMethod.PUT,"/v1/me",BOB,Map.of("name","Original name","username","alice","timeZone","UTC")),409);
        result(call(HttpMethod.PUT,"/v1/me",BOB,Map.of("name","Original name","username","bob","timeZone","Not/AZone")),400);
        result(call(HttpMethod.PUT,"/v1/me",BOB,Map.of("name","Original name","username","bob","timeZone","UTC","birthdayMonth",2,"birthdayDay",30)),400);
    }
    @Test void fullBirthDateIsPrivateAndIdentityFieldsCannotBeChanged() throws Exception {
        var body=new HashMap<String,Object>();body.put("username","alice");body.put("timeZone","UTC");body.put("birthDate","2000-02-29");
        var saved=result(call(HttpMethod.PUT,"/v1/me",ALICE,body),200);
        assertThat(saved.get("birthDate").asText()).isEqualTo("2000-02-29");
        assertThat(saved.get("birthdayMonth").asInt()).isEqualTo(2);
        assertThat(saved.get("birthdayDay").asInt()).isEqualTo(29);
        assertThat(saved.get("name").asText()).isEqualTo("Original name");
        var publicView=result(call(HttpMethod.GET,"/v1/users/"+ALICE,BOB,null),200);
        assertThat(publicView.has("birthDate")).isFalse();assertThat(publicView.has("age")).isFalse();
        body.put("name","Changed name");
        assertThat(result(call(HttpMethod.PUT,"/v1/me",ALICE,body),400).get("code").asText()).isEqualTo("identity_read_only");
        body.remove("name");body.put("email","changed@example.com");
        result(call(HttpMethod.PUT,"/v1/me",ALICE,body),400);body.remove("email");
        body.put("birthDate","2026-10-22");
        assertThat(result(call(HttpMethod.PUT,"/v1/me",ALICE,body),400).get("code").asText()).isEqualTo("future_birth_date");
        body.put("birthDate","2001-02-29");result(call(HttpMethod.PUT,"/v1/me",ALICE,body),400);
        assertThat(result(call(HttpMethod.GET,"/v1/me",ALICE,null),200).get("birthDate").asText()).isEqualTo("2000-02-29");
    }
    @Test void legacyBirthdayUpdatesNeverInventABirthYearAndPreserveAnExistingFullDate() throws Exception {
        var body=new HashMap<String,Object>();body.put("username","alice");body.put("timeZone","UTC");
        body.put("birthdayMonth",10);body.put("birthdayDay",20);
        assertThat(result(call(HttpMethod.PUT,"/v1/me",ALICE,body),200).get("birthDate").isNull()).isTrue();
        body.put("birthDate","1990-10-20");
        result(call(HttpMethod.PUT,"/v1/me",ALICE,body),200);
        body.remove("birthDate");
        assertThat(result(call(HttpMethod.PUT,"/v1/me",ALICE,body),200).get("birthDate").asText()).isEqualTo("1990-10-20");
        body.put("birthDate","1990-10-21");result(call(HttpMethod.PUT,"/v1/me",ALICE,body),400);
        body.put("birthDate",null);body.put("birthdayMonth",null);body.put("birthdayDay",null);
        var cleared=result(call(HttpMethod.PUT,"/v1/me",ALICE,body),200);
        assertThat(cleared.get("birthDate").isNull()).isTrue();assertThat(cleared.get("birthdayMonth").isNull()).isTrue();
    }
    @Test void newAccountsHaveAnEmptySummaryAndZeroBadgeProgress() throws Exception {
        var summary=result(call(HttpMethod.GET,"/v1/readings/summary",ALICE,null),200);
        assertThat(summary.get("count").asInt()).isZero();
        assertThat(summary.get("averageGlucoseMgDl").isNull()).isTrue();
        assertThat(summary.get("latest").isNull()).isTrue();
        var badges=result(call(HttpMethod.GET,"/v1/me/badges",ALICE,null),200);
        assertThat(badges.get("readingCount").asInt()).isZero();
        assertThat(badges.get("currentStreak").asInt()).isZero();
        assertThat(badges.get("badges").size()).isEqualTo(10);
    }
    @Test void optionalInsulinAndIdempotentReadingCreation() throws Exception {
        var id=UUID.randomUUID();var body=readingBody(id,94,"2026-10-20T10:30:00Z","fasting","America/Sao_Paulo",null);
        assertThat(result(call(HttpMethod.POST,"/v1/readings",ALICE,body),201).get("insulinUnits").isNull()).isTrue();
        result(call(HttpMethod.POST,"/v1/readings",ALICE,body),201);
        assertThat(result(call(HttpMethod.GET,"/v1/readings/summary",ALICE,null),200).get("count").asInt()).isEqualTo(1);
        body.put("insulinUnits",new BigDecimal("0.125"));
        result(call(HttpMethod.POST,"/v1/readings",ALICE,body),409);
        body.put("id",UUID.randomUUID());body.put("moment","after_breakfast");
        assertThat(result(call(HttpMethod.POST,"/v1/readings",ALICE,body),201).get("insulinUnits").decimalValue()).isEqualByComparingTo("0.125");
        body.put("id",UUID.randomUUID());body.put("insulinUnits",new BigDecimal("-1"));
        result(call(HttpMethod.POST,"/v1/readings",ALICE,body),400);
        body.put("insulinUnits",BigDecimal.ZERO);body.put("measuredAt","2026-10-22T00:00:00Z");
        result(call(HttpMethod.POST,"/v1/readings",ALICE,body),400);
    }
    @Test void dateFilterUsesHistoricalLocalDayAndCombinesWithMoment() throws Exception {
        reading(ALICE,"2026-10-21T02:59:00Z","fasting"); // still Oct 20 in Sao Paulo
        reading(ALICE,"2026-10-21T03:01:00Z","fasting");
        reading(ALICE,"2026-10-20T15:00:00Z","after_lunch");
        assertThat(result(call(HttpMethod.GET,"/v1/readings?from=2026-10-20&to=2026-10-20",ALICE,null),200).get("items").size()).isEqualTo(2);
        assertThat(result(call(HttpMethod.GET,"/v1/readings?from=2026-10-20&to=2026-10-20&moment=fasting",ALICE,null),200).get("items").size()).isEqualTo(1);
        result(call(HttpMethod.GET,"/v1/readings?from=2026-10-21&to=2026-10-20",ALICE,null),400);
        result(call(HttpMethod.GET,"/v1/readings?moment=Jejum",ALICE,null),400);
    }
    @Test void readingsCannotBeReadOrChangedByAnotherUserAndVersionsPreventLostUpdates() throws Exception {
        var saved=reading(ALICE,"2026-10-20T10:00:00Z","fasting");var id=id(saved);
        result(call(HttpMethod.GET,"/v1/readings/"+id,BOB,null),404);
        var update=readingBody(null,120,"2026-10-20T10:00:00Z","fasting","UTC",null);update.remove("id");update.put("version",0);
        result(call(HttpMethod.PUT,"/v1/readings/"+id,BOB,update),404);
        result(call(HttpMethod.PUT,"/v1/readings/"+id,ALICE,update),200);
        result(call(HttpMethod.PUT,"/v1/readings/"+id,ALICE,update),409);
        result(call(HttpMethod.DELETE,"/v1/readings/"+id+"?version=0",ALICE,null),409);
        result(call(HttpMethod.DELETE,"/v1/readings/"+id+"?version=1",BOB,null),404);
    }
    @Test void paginationUsesStableTieBreaker() throws Exception {
        for(String moment:List.of("fasting","after_breakfast","after_lunch")) reading(ALICE,"2026-10-20T10:00:00Z",moment);
        var first=result(call(HttpMethod.GET,"/v1/readings?limit=2",ALICE,null),200);
        var second=result(call(HttpMethod.GET,"/v1/readings?limit=2&cursor="+first.get("nextCursor").asText(),ALICE,null),200);
        assertThat(first.get("items").size()).isEqualTo(2);assertThat(second.get("items").size()).isEqualTo(1);
        assertThat(first.get("items").get(0).get("id")).isNotEqualTo(second.get("items").get(0).get("id"));
        result(call(HttpMethod.GET,"/v1/readings?cursor=invalid",ALICE,null),400);
        result(call(HttpMethod.GET,"/v1/readings?limit=1000",ALICE,null),400);
    }
    @Test void emergencyContactIsOnlyAvailableOnOwnProfile() throws Exception {
        result(call(HttpMethod.PUT,"/v1/me/emergency-contact",ALICE,Map.of("name","Ana","phone","+5511999991234","relationship","Sister")),200);
        result(call(HttpMethod.GET,"/v1/me/emergency-contact",BOB,null),404);
        result(call(HttpMethod.DELETE,"/v1/me/emergency-contact",BOB,null),204);
        assertThat(result(call(HttpMethod.GET,"/v1/me/emergency-contact",ALICE,null),200).get("name").asText()).isEqualTo("Ana");
        result(call(HttpMethod.DELETE,"/v1/me/emergency-contact",ALICE,null),204);
        result(call(HttpMethod.GET,"/v1/me/emergency-contact",ALICE,null),404);
    }
    @Test void approvedFollowingOnlyGrantsAccessToSharedPostsAndIsDirectional() throws Exception {
        profile(ALICE,"alice");profile(BOB,"bob");profile(CAROL,"carol");
        UUID reading=id(reading(ALICE,"2026-10-20T10:00:00Z","fasting"));UUID post=id(share(ALICE,"reading",reading));
        UUID relationship=follow(BOB,ALICE);
        assertThat(result(call(HttpMethod.GET,"/v1/feed",BOB,null),200).get("items").size()).isZero();
        result(call(HttpMethod.GET,"/v1/posts/"+post,BOB,null),404);
        result(call(HttpMethod.PUT,"/v1/follow-requests/"+relationship+"/decision",CAROL,Map.of("accept",true,"version",0)),404);
        accept(ALICE,relationship,0);
        assertThat(result(call(HttpMethod.GET,"/v1/feed",BOB,null),200).get("items").size()).isEqualTo(1);
        result(call(HttpMethod.GET,"/v1/readings/"+reading,BOB,null),404);
        result(call(HttpMethod.GET,"/v1/me/emergency-contact",BOB,null),404);
        UUID bobPost=id(share(BOB,"reading",id(reading(BOB,"2026-10-20T11:00:00Z","fasting"))));
        result(call(HttpMethod.GET,"/v1/posts/"+bobPost,ALICE,null),404);
        result(call(HttpMethod.DELETE,"/v1/follows/"+relationship+"?version=1",ALICE,null),204);
        result(call(HttpMethod.GET,"/v1/posts/"+post,BOB,null),404);
    }
    @Test void followRequestsCanBeCancelledRefusedAndRetriedWithoutDuplicateNotifications() throws Exception {
        profile(ALICE,"alice");profile(BOB,"bob");
        UUID relationship=follow(BOB,ALICE);assertThat(follow(BOB,ALICE)).isEqualTo(relationship);
        assertThat(result(call(HttpMethod.GET,"/v1/notifications",ALICE,null),200).get("items").size()).isEqualTo(1);
        result(call(HttpMethod.PUT,"/v1/follow-requests/"+relationship+"/cancel",BOB,Map.of("version",0)),200);
        result(call(HttpMethod.POST,"/v1/follow-requests/"+ALICE,BOB,null),201);
        result(call(HttpMethod.PUT,"/v1/follow-requests/"+relationship+"/decision",ALICE,Map.of("accept",true,"version",0)),409);
        result(call(HttpMethod.PUT,"/v1/follow-requests/"+relationship+"/decision",ALICE,Map.of("accept",false,"version",2)),200);
        var retry=result(call(HttpMethod.POST,"/v1/follow-requests/"+ALICE,BOB,null),201);
        assertThat(retry.get("version").asInt()).isEqualTo(4);accept(ALICE,relationship,4);
        result(call(HttpMethod.POST,"/v1/follow-requests/"+BOB,BOB,null),400);
    }
    @Test void blockingRevokesFeedAndUnblockingDoesNotRestoreApproval() throws Exception {
        profile(ALICE,"alice");profile(BOB,"bob");
        UUID post=id(share(ALICE,"reading",id(reading(ALICE,"2026-10-20T10:00:00Z","fasting"))));
        var relationship=follow(BOB,ALICE);accept(ALICE,relationship,0);
        result(call(HttpMethod.PUT,"/v1/blocks/"+BOB,ALICE,null),204);
        result(call(HttpMethod.GET,"/v1/posts/"+post,BOB,null),404);
        result(call(HttpMethod.POST,"/v1/follow-requests/"+ALICE,BOB,null),404);
        assertThat(result(call(HttpMethod.GET,"/v1/users?query=alice",BOB,null),200).size()).isZero();
        result(call(HttpMethod.DELETE,"/v1/blocks/"+BOB,ALICE,null),204);
        result(call(HttpMethod.GET,"/v1/posts/"+post,BOB,null),404);
    }
    @Test void badgesCountDistinctDaysAndRemainGrantedAfterDeletion() throws Exception {
        reading(ALICE,"2026-10-18T10:00:00Z","fasting");
        reading(ALICE,"2026-10-18T11:00:00Z","after_breakfast");
        reading(ALICE,"2026-10-19T10:00:00Z","fasting");
        var finalReading=reading(ALICE,"2026-10-20T10:00:00Z","fasting");
        var overview=result(call(HttpMethod.GET,"/v1/me/badges",ALICE,null),200);
        assertThat(overview.get("currentStreak").asInt()).isEqualTo(3);
        assertThat(overview.get("longestStreak").asInt()).isEqualTo(3);
        assertThat(db.sql("SELECT count(*) FROM earned_badges WHERE user_id=:id").param("id",ALICE).query(Long.class).single()).isEqualTo(2);
        result(call(HttpMethod.DELETE,"/v1/readings/"+id(finalReading)+"?version=0",ALICE,null),204);
        overview=result(call(HttpMethod.GET,"/v1/me/badges",ALICE,null),200);
        assertThat(overview.get("currentStreak").asInt()).isZero();
        assertThat(overview.get("longestStreak").asInt()).isEqualTo(2);
        assertThat(db.sql("SELECT count(*) FROM earned_badges WHERE user_id=:id").param("id",ALICE).query(Long.class).single()).isEqualTo(2);
        assertThat(db.sql("SELECT count(*) FROM earned_badges WHERE user_id=:id AND source_reading_id IS NULL").param("id",ALICE).query(Long.class).single()).isEqualTo(1);
    }
    @Test void retroactiveReadingsFillStreakGapsAndGrantCountMilestonesOnce() throws Exception {
        reading(ALICE,"2026-10-18T10:00:00Z","fasting");reading(ALICE,"2026-10-20T10:00:00Z","fasting");
        assertThat(result(call(HttpMethod.GET,"/v1/me/badges",ALICE,null),200).get("longestStreak").asInt()).isEqualTo(1);
        reading(ALICE,"2026-10-19T10:00:00Z","fasting");
        for(int i=0;i<8;i++) reading(ALICE,"2026-10-"+(10+i)+"T11:00:00Z","after_breakfast");
        assertThat(db.sql("SELECT count(*) FROM earned_badges e JOIN badges b ON b.id=e.badge_id WHERE e.user_id=:id AND b.code='readings_10'").param("id",ALICE).query(Long.class).single()).isEqualTo(1);
        assertThat(result(call(HttpMethod.GET,"/v1/me/badges",ALICE,null),200).get("longestStreak").asInt()).isEqualTo(11);
    }
    @Test void annualBadgesUseOccurrenceYearAndBirthdayIsOptional() throws Exception {
        reading(ALICE,"2025-01-01T10:00:00Z","fasting");reading(ALICE,"2026-01-01T10:00:00Z","fasting");
        reading(ALICE,"2026-10-20T10:00:00Z","fasting");
        assertThat(db.sql("SELECT count(*) FROM earned_badges e JOIN badges b ON b.id=e.badge_id WHERE e.user_id=:id AND b.code='birthday_reading'").param("id",ALICE).query(Long.class).single()).isZero();
        result(call(HttpMethod.PUT,"/v1/me",ALICE,Map.of("name","Original name","username","alice","timeZone","America/Sao_Paulo","birthdayMonth",10,"birthdayDay",20)),200);
        var editions=db.sql("SELECT e.edition FROM earned_badges e JOIN badges b ON b.id=e.badge_id WHERE e.user_id=:id AND b.code='new_year_reading' ORDER BY edition").param("id",ALICE).query(String.class).list();
        assertThat(editions).containsExactly("2025","2026");
        assertThat(db.sql("SELECT count(*) FROM earned_badges e JOIN badges b ON b.id=e.badge_id WHERE e.user_id=:id AND b.code='birthday_reading'").param("id",ALICE).query(Long.class).single()).isEqualTo(1);
    }
    @Test void postsAreOptionalOwnedAndCascadeWhenSourceIsDeleted() throws Exception {
        var reading=reading(ALICE,"2026-10-20T10:00:00Z","fasting");
        assertThat(result(call(HttpMethod.GET,"/v1/feed",ALICE,null),200).get("items").size()).isZero();
        result(call(HttpMethod.POST,"/v1/posts",BOB,Map.of("type","reading","referenceId",id(reading))),404);
        var post=share(ALICE,"reading",id(reading));
        assertThat(id(share(ALICE,"reading",id(reading)))).isEqualTo(id(post));
        result(call(HttpMethod.PUT,"/v1/posts/"+id(post),BOB,Map.of("caption","Not mine")),404);
        result(call(HttpMethod.DELETE,"/v1/readings/"+id(reading)+"?version=0",ALICE,null),204);
        result(call(HttpMethod.GET,"/v1/posts/"+id(post),ALICE,null),404);
    }
    @Test void earnedBadgesCanBeSharedAndNotificationsArePrivate() throws Exception {
        reading(ALICE,"2026-10-20T10:00:00Z","fasting");
        UUID award=db.sql("SELECT id FROM earned_badges WHERE user_id=:id").param("id",ALICE).query(UUID.class).single();
        assertThat(share(ALICE,"badge",award).get("badge").get("definition").get("code").asText()).isEqualTo("first_reading");
        var notifications=result(call(HttpMethod.GET,"/v1/notifications",ALICE,null),200).get("items");
        UUID notification=id(notifications.get(0));
        result(call(HttpMethod.PUT,"/v1/notifications/"+notification+"/read",BOB,null),404);
        result(call(HttpMethod.PUT,"/v1/notifications/"+notification+"/read",ALICE,null),204);
        assertThat(result(call(HttpMethod.GET,"/v1/notifications",BOB,null),200).get("items").size()).isZero();
        result(call(HttpMethod.GET,"/v1/me/badges/"+award,BOB,null),404);
    }
    @Test void nanosecondInputsAreNormalizedToPostgresPrecision() throws Exception {
        var body=readingBody(UUID.randomUUID(),94,"2026-10-20T10:30:00.123456789Z","fasting","UTC",null);
        var first=result(call(HttpMethod.POST,"/v1/readings",ALICE,body),201);
        var repeated=result(call(HttpMethod.POST,"/v1/readings",ALICE,body),201);
        assertThat(first.get("measuredAt").asText()).isEqualTo("2026-10-20T10:30:00.123456Z");
        assertThat(id(first)).isEqualTo(id(repeated));
    }
    @Test void concurrentReadingsDoNotDuplicateBadgeGrants() throws Exception {
        profile(ALICE,"alice");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var tasks=new ArrayList<java.util.concurrent.Callable<UUID>>();
            for(String moment:List.of("fasting","after_breakfast","after_lunch")) tasks.add(() -> id(reading(ALICE,"2026-10-20T10:00:00Z",moment)));
            for(var future:pool.invokeAll(tasks)) assertThat(future.get()).isNotNull();
        } finally { pool.shutdownNow(); }
        assertThat(db.sql("SELECT count(*) FROM readings WHERE user_id=:id").param("id",ALICE).query(Long.class).single()).isEqualTo(3);
        assertThat(db.sql("SELECT count(*) FROM earned_badges WHERE user_id=:id").param("id",ALICE).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM notifications WHERE recipient_id=:id AND type='badge_earned'").param("id",ALICE).query(Long.class).single()).isEqualTo(1);
    }
    @Test void duplicateReadingsAreRejectedAcrossIdsAndOnUpdate() throws Exception {
        var first=reading(ALICE,"2026-10-20T10:00:00Z","fasting");
        var duplicate=readingBody(UUID.randomUUID(),200,"2026-10-20T11:00:00Z","fasting","America/Sao_Paulo",null);
        assertThat(result(call(HttpMethod.POST,"/v1/readings",ALICE,duplicate),409).get("code").asText()).isEqualTo("duplicate_reading");
        reading(BOB,"2026-10-20T10:00:00Z","fasting");
        reading(ALICE,"2026-10-19T10:00:00Z","fasting");
        var other=reading(ALICE,"2026-10-20T11:00:00Z","after_lunch");
        duplicate.remove("id");duplicate.put("version",0);
        assertThat(result(call(HttpMethod.PUT,"/v1/readings/"+id(other),ALICE,duplicate),409).get("code").asText()).isEqualTo("duplicate_reading");
        assertThat(result(call(HttpMethod.GET,"/v1/readings/summary",ALICE,null),200).get("count").asInt()).isEqualTo(3);
        // A different zone cannot duplicate the same measurement on another local day.
        var exact=readingBody(UUID.randomUUID(),110,"2026-10-20T10:00:00Z","fasting","Pacific/Kiritimati",BigDecimal.ZERO);
        result(call(HttpMethod.POST,"/v1/readings",ALICE,exact),409);
        result(call(HttpMethod.DELETE,"/v1/readings/"+id(first)+"?version=0",ALICE,null),204);
        reading(ALICE,"2026-10-20T10:00:00Z","fasting");
    }
    @Test void otherReadingsAllowMultipleTimesButRejectTheSameInstant() throws Exception {
        var first=reading(ALICE,"2026-10-20T10:00:00Z","other");
        var second=reading(ALICE,"2026-10-20T10:00:00.000001Z","other");
        var duplicate=readingBody(UUID.randomUUID(),200,"2026-10-20T07:00:00-03:00","other","America/Sao_Paulo",BigDecimal.ZERO);
        assertThat(result(call(HttpMethod.POST,"/v1/readings",ALICE,duplicate),409).get("code").asText()).isEqualTo("duplicate_reading");
        // Repeating the original ID and payload is still idempotent.
        result(call(HttpMethod.POST,"/v1/readings",ALICE,
            readingBody(id(first),110,"2026-10-20T10:00:00Z","other","America/Sao_Paulo",new BigDecimal("2.5"))),201);
        duplicate.remove("id");duplicate.put("version",0);
        result(call(HttpMethod.PUT,"/v1/readings/"+id(second),ALICE,duplicate),409);
        reading(BOB,"2026-10-20T10:00:00Z","other");
        reading(ALICE,"2026-10-20T10:00:00Z","fasting");
        assertThat(result(call(HttpMethod.GET,"/v1/readings/summary",ALICE,null),200).get("count").asInt()).isEqualTo(3);
    }
    @Test void concurrentDuplicateRequestsCreateOnlyOneReadingAndBadge() throws Exception {
        profile(ALICE,"alice");
        var pool=java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            var tasks=new ArrayList<java.util.concurrent.Callable<Integer>>();
            for(int i=0;i<3;i++) tasks.add(() -> call(HttpMethod.POST,"/v1/readings",ALICE,
                readingBody(UUID.randomUUID(),110,"2026-10-20T10:00:00Z","fasting","UTC",null))
                .andReturn().getResponse().getStatus());
            var statuses=new ArrayList<Integer>();
            for(var future:pool.invokeAll(tasks)) statuses.add(future.get());
            assertThat(statuses).containsExactlyInAnyOrder(201,409,409);
        } finally { pool.shutdownNow(); }
        assertThat(db.sql("SELECT count(*) FROM readings WHERE user_id=:id").param("id",ALICE).query(Long.class).single()).isEqualTo(1);
        assertThat(db.sql("SELECT count(*) FROM earned_badges WHERE user_id=:id").param("id",ALICE).query(Long.class).single()).isEqualTo(1);
    }
    @Test void privateSchemaIsNotGrantedToPublic() {
        assertThat(db.sql("SELECT count(*) FROM information_schema.role_table_grants WHERE table_schema='glicnote' AND grantee='PUBLIC'").query(Long.class).single()).isZero();
        for(String role:List.of("anon","authenticated","service_role")) {
            assertThat(db.sql("SELECT has_schema_privilege(:role,'glicnote','USAGE')").param("role",role).query(Boolean.class).single()).isFalse();
            assertThat(db.sql("SELECT has_table_privilege(:role,'glicnote.readings','SELECT')").param("role",role).query(Boolean.class).single()).isFalse();
        }
    }
}
