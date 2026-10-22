package com.glicnote.api;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import static com.glicnote.api.Models.*;

@Service
public class BadgeService {
    final JdbcClient db;
    final Clock clock;
    BadgeService(JdbcClient db,Clock clock) { this.db=db; this.clock=clock; }
    record DayEvidence(LocalDate localDate,UUID sourceId,OffsetDateTime firstAt) {}
    record Evidence(UUID id,OffsetDateTime measuredAt) {}
    record Stats(long count,int current,int longest,List<DayEvidence> days) {}
    List<Badge> definitions() { return db.sql("SELECT * FROM badges WHERE active ORDER BY sort_order,code").query(Badge.class).list(); }
    Stats stats(UUID user) {
        long count=db.sql("SELECT count(*) FROM readings WHERE user_id=:user").param("user",user).query(Long.class).single();
        var days=db.sql("""
            SELECT DISTINCT ON(local_date) local_date,id AS source_id,measured_at AS first_at
            FROM readings WHERE user_id=:user ORDER BY local_date,measured_at,id
            """).param("user",user).query(DayEvidence.class).list();
        int longest=0,run=0;
        LocalDate previous=null;
        for(var day:days) {
            run=previous!=null && previous.plusDays(1).equals(day.localDate())?run+1:1;
            longest=Math.max(longest,run); previous=day.localDate();
        }
        String zone=db.sql("SELECT time_zone FROM profiles WHERE id=:user").param("user",user).query(String.class).single();
        LocalDate today=LocalDate.now(clock.withZone(ZoneId.of(zone)));
        var dates=new HashSet<LocalDate>(); days.forEach(day -> dates.add(day.localDate()));
        LocalDate start=dates.contains(today)?today:today.minusDays(1);
        int current=0;
        while(dates.contains(start)) { current++; start=start.minusDays(1); }
        return new Stats(count,current,longest,days);
    }
    // Caller holds the profile row lock inside its transaction, serializing grants and reading mutations.
    void evaluate(UUID user) {
        var stats=stats(user);
        var profile=db.sql("SELECT * FROM profiles WHERE id=:user").param("user",user).query(Profile.class).single();
        for(var badge:definitions()) {
            switch(badge.ruleType()) {
                case "reading_count" -> {
                    if(stats.count()>=badge.target()) {
                        var evidence=db.sql("SELECT id,measured_at FROM readings WHERE user_id=:user ORDER BY measured_at,id OFFSET :offset LIMIT 1")
                            .param("user",user).param("offset",badge.target()-1).query(Evidence.class).single();
                        grant(user,badge,"lifetime",evidence.id(),evidence.measuredAt());
                    }
                }
                case "day_streak" -> {
                    int run=0; LocalDate previous=null;
                    for(var day:stats.days()) {
                        run=previous!=null && previous.plusDays(1).equals(day.localDate())?run+1:1;
                        if(run>=badge.target()) { grant(user,badge,"lifetime",day.sourceId(),day.firstAt()); break; }
                        previous=day.localDate();
                    }
                }
                case "special_date" -> {
                    var event=db.sql("SELECT * FROM special_dates WHERE id=:id AND active")
                        .param("id",badge.specialDateId()).query(SpecialDate.class).optional();
                    if(event.isEmpty()) continue;
                    for(var day:stats.days()) {
                        if(matches(event.get(),day.localDate(),profile)) {
                            String edition="annual".equals(badge.recurrence())?String.valueOf(day.localDate().getYear()):"lifetime";
                            grant(user,badge,edition,day.sourceId(),day.firstAt());
                        }
                    }
                }
                default -> throw new IllegalStateException("Unknown badge rule");
            }
        }
    }
    private boolean matches(SpecialDate event,LocalDate date,Profile profile) {
        return switch(event.kind()) {
            case "specific_date" -> date.equals(event.specificDate());
            case "fixed_annual" -> date.getMonthValue()==event.month() && date.getDayOfMonth()==event.day();
            case "birthday" -> profile.birthdayMonth()!=null && date.getMonthValue()==profile.birthdayMonth() && date.getDayOfMonth()==profile.birthdayDay();
            default -> false;
        };
    }
    private void grant(UUID user,Badge badge,String edition,UUID source,OffsetDateTime achieved) {
        var awarded=db.sql("""
            INSERT INTO earned_badges(user_id,badge_id,edition,rule_version,source_reading_id,achieved_at)
            VALUES(:user,:badge,:edition,:version,:source,:achieved)
            ON CONFLICT(user_id,badge_id,edition) DO NOTHING RETURNING id
            """).param("user",user).param("badge",badge.id()).param("edition",edition).param("version",badge.ruleVersion())
            .param("source",source).param("achieved",achieved).query(UUID.class).optional();
        awarded.ifPresent(id -> db.sql("""
            INSERT INTO notifications(recipient_id,type,earned_badge_id,event_key)
            VALUES(:user,'badge_earned',:award,:key) ON CONFLICT(event_key) DO NOTHING
            """).param("user",user).param("award",id).param("key","badge_earned:"+id).update());
    }
    BadgeOverview overview(UUID user) {
        var stats=stats(user);
        var awards=db.sql("SELECT * FROM earned_badges WHERE user_id=:user ORDER BY earned_at DESC,id DESC")
            .param("user",user).query(EarnedBadge.class).list();
        String zone=db.sql("SELECT time_zone FROM profiles WHERE id=:user").param("user",user).query(String.class).single();
        String year=String.valueOf(LocalDate.now(clock.withZone(ZoneId.of(zone))).getYear());
        var progress=new ArrayList<BadgeProgress>();
        for(var badge:definitions()) {
            var owned=awards.stream().filter(award -> award.badgeId().equals(badge.id())).toList();
            boolean achieved=owned.stream().anyMatch(award -> "once".equals(badge.recurrence()) || award.edition().equals(year));
            long current=switch(badge.ruleType()) { case "reading_count" -> stats.count(); case "day_streak" -> stats.current(); default -> achieved?1:0; };
            long best="day_streak".equals(badge.ruleType())?stats.longest():current;
            progress.add(new BadgeProgress(badge,current,best,achieved,owned));
        }
        return new BadgeOverview(stats.count(),stats.current(),stats.longest(),progress);
    }
    EarnedBadgeView earned(UUID user,UUID id) {
        var award=db.sql("SELECT * FROM earned_badges WHERE id=:id AND user_id=:user")
            .param("id",id).param("user",user).query(EarnedBadge.class).optional().orElseThrow(ApiException::notFound);
        var definition=db.sql("SELECT * FROM badges WHERE id=:id").param("id",award.badgeId()).query(Badge.class).single();
        return new EarnedBadgeView(award,definition);
    }
}
