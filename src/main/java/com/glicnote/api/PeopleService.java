package com.glicnote.api;

import java.time.*;
import java.net.URI;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.glicnote.api.Models.*;

@Service
public class PeopleService {
    final JdbcClient db;
    final BadgeService badges;
    PeopleService(JdbcClient db,BadgeService badges) { this.db=db; this.badges=badges; }
    UUID actor(Jwt jwt) {
        var id=UUID.fromString(jwt.getSubject());
        Map<String,Object> metadata=jwt.getClaimAsMap("user_metadata");
        String name=metadata==null?null:shortText(metadata.get("full_name"),120);
        String photo=metadata==null?null:photoUrl(metadata.get("avatar_url"));
        if(photo==null && metadata!=null) photo=photoUrl(metadata.get("picture"));
        db.sql("INSERT INTO profiles(id,name,email,photo_url) VALUES(:id,:name,:email,:photo) ON CONFLICT(id) DO UPDATE SET photo_url=EXCLUDED.photo_url,updated_at=now() "
            +"WHERE EXCLUDED.photo_url IS NOT NULL AND profiles.photo_url IS DISTINCT FROM EXCLUDED.photo_url")
            .param("id",id).param("name",name).param("email",shortText(jwt.getClaimAsString("email"),320)).param("photo",photo).update();
        return id;
    }
    static String photoUrl(Object value) {
        if(!(value instanceof String text) || text.length()>2048) return null;
        try {
            var uri=URI.create(text);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null ? text : null;
        } catch(IllegalArgumentException e) { return null; }
    }
    static String shortText(Object value,int max) {
        if(!(value instanceof String text) || text.isBlank()) return null;
        return text.substring(0,Math.min(text.length(),max));
    }
    Profile me(UUID id) { return db.sql("SELECT * FROM profiles WHERE id=:id").param("id",id).query(Profile.class).single(); }
    void lock(UUID id) { db.sql("SELECT id FROM profiles WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).single(); }
    @Transactional
    Profile update(UUID id,ProfileInput input) {
        zone(input.timeZone());
        if((input.birthdayMonth()==null)!=(input.birthdayDay()==null)) throw ApiException.invalid("invalid_birthday");
        if(input.birthdayMonth()!=null) {
            try { MonthDay.of(input.birthdayMonth(),input.birthdayDay()); }
            catch(DateTimeException e) { throw ApiException.invalid("invalid_birthday"); }
        }
        lock(id);
        db.sql("""
            UPDATE profiles SET name=:name,username=:username,time_zone=:zone,birthday_month=:month,birthday_day=:day,updated_at=now() WHERE id=:id
            """).param("name",input.name().trim()).param("username",input.username()).param("zone",input.timeZone())
            .param("month",input.birthdayMonth()).param("day",input.birthdayDay()).param("id",id).update();
        badges.evaluate(id);
        return me(id);
    }
    static ZoneId zone(String id) {
        try {
            if(!ZoneId.getAvailableZoneIds().contains(id)) throw new DateTimeException("Unknown zone");
            return ZoneId.of(id);
        } catch(DateTimeException e) { throw ApiException.invalid("invalid_time_zone"); }
    }
    PublicProfile publicProfile(UUID viewer,UUID target) {
        if(blocked(viewer,target)) throw ApiException.notFound();
        return db.sql("SELECT id,name,username,photo_url FROM profiles WHERE id=:id")
            .param("id",target).query(PublicProfile.class).optional().orElseThrow(ApiException::notFound);
    }
    boolean blocked(UUID a,UUID b) {
        return db.sql("SELECT EXISTS(SELECT 1 FROM blocks WHERE (blocker_id=:a AND blocked_id=:b) OR (blocker_id=:b AND blocked_id=:a))")
            .param("a",a).param("b",b).query(Boolean.class).single();
    }
    List<PublicProfile> search(UUID viewer,String query,int limit) {
        if(query==null || query.strip().length()<2 || query.length()>80) throw ApiException.invalid("invalid_search");
        return db.sql("""
            SELECT id,name,username,photo_url FROM profiles p WHERE username IS NOT NULL AND id<>:viewer
            AND (starts_with(username,:query) OR strpos(lower(name),:query)>0)
            AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker_id=:viewer AND b.blocked_id=p.id) OR (b.blocker_id=p.id AND b.blocked_id=:viewer))
            ORDER BY username LIMIT :limit
            """).param("viewer",viewer).param("query",query.strip().toLowerCase(Locale.ROOT)).param("limit",Paging.limit(limit)).query(PublicProfile.class).list();
    }
    EmergencyContact contact(UUID id) {
        return db.sql("SELECT name,phone,relationship,updated_at FROM emergency_contacts WHERE user_id=:id")
            .param("id",id).query(EmergencyContact.class).optional().orElseThrow(ApiException::notFound);
    }
    @Transactional
    EmergencyContact saveContact(UUID id,ContactInput input) {
        db.sql("""
            INSERT INTO emergency_contacts(user_id,name,phone,relationship) VALUES(:id,:name,:phone,:relationship)
            ON CONFLICT(user_id) DO UPDATE SET name=excluded.name,phone=excluded.phone,relationship=excluded.relationship,updated_at=now()
            """).param("id",id).param("name",input.name().trim()).param("phone",input.phone())
            .param("relationship",input.relationship()).update();
        return contact(id);
    }
    void deleteContact(UUID id) { db.sql("DELETE FROM emergency_contacts WHERE user_id=:id").param("id",id).update(); }
}
