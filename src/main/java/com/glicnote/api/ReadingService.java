package com.glicnote.api;

import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.glicnote.api.Models.*;

@Service
public class ReadingService {
    final JdbcClient db; final PeopleService people; final BadgeService badges; final Clock clock;
    ReadingService(JdbcClient db,PeopleService people,BadgeService badges,Clock clock) {
        this.db=db; this.people=people; this.badges=badges; this.clock=clock;
    }
    Reading get(UUID user,UUID id) {
        return db.sql("SELECT * FROM readings WHERE user_id=:user AND id=:id").param("user",user).param("id",id)
            .query(Reading.class).optional().orElseThrow(ApiException::notFound);
    }
    LocalDate localDate(OffsetDateTime at,String zone) {
        if(at.toInstant().isAfter(clock.instant())) throw ApiException.invalid("future_reading");
        return at.atZoneSameInstant(PeopleService.zone(zone)).toLocalDate();
    }
    @Transactional
    Reading create(UUID user,ReadingInput input) {
        LocalDate day=localDate(input.measuredAt(),input.timeZone());
        people.lock(user);
        UUID id=input.id()==null?UUID.randomUUID():input.id();
        ensureAvailable(user,id,input.glucoseMgDl(),input.moment(),input.measuredAt(),day);
        db.sql("""
            INSERT INTO readings(id,user_id,glucose_mg_dl,insulin_units,moment,measured_at,time_zone,local_date)
            VALUES(:id,:user,:glucose,:insulin,:moment,:at,:zone,:day) ON CONFLICT(id) DO NOTHING
            """).param("id",id).param("user",user).param("glucose",input.glucoseMgDl()).param("insulin",input.insulinUnits())
            .param("moment",input.moment()).param("at",input.measuredAt().truncatedTo(ChronoUnit.MICROS)).param("zone",input.timeZone()).param("day",day).update();
        Reading reading=get(user,id);
        if(reading.glucoseMgDl()!=input.glucoseMgDl() || !decimalEqual(reading.insulinUnits(),input.insulinUnits())
            || !reading.moment().equals(input.moment()) || !reading.measuredAt().toInstant().equals(input.measuredAt().truncatedTo(ChronoUnit.MICROS).toInstant())
            || !reading.timeZone().equals(input.timeZone())) throw ApiException.conflict("reading_id_reused");
        badges.evaluate(user);
        return reading;
    }
    private void ensureAvailable(UUID user,UUID id,int glucose,String moment,OffsetDateTime at,LocalDate day) {
        boolean duplicate=db.sql("""
            SELECT EXISTS(SELECT 1 FROM readings WHERE user_id=:user AND id<>:id AND moment=:moment
              AND ((:moment<>'other' AND local_date=:day)
                OR (measured_at=:at AND (:moment='other' OR glucose_mg_dl=:glucose))))
            """).param("user",user).param("id",id).param("moment",moment).param("day",day)
            .param("at",at.truncatedTo(ChronoUnit.MICROS)).param("glucose",glucose).query(Boolean.class).single();
        if(duplicate) throw ApiException.conflict("duplicate_reading");
    }
    private boolean decimalEqual(BigDecimal a,BigDecimal b) { return a==null?b==null:b!=null && a.compareTo(b)==0; }
    @Transactional
    Reading update(UUID user,UUID id,ReadingUpdate input) {
        LocalDate day=localDate(input.measuredAt(),input.timeZone());
        people.lock(user); get(user,id);
        ensureAvailable(user,id,input.glucoseMgDl(),input.moment(),input.measuredAt(),day);
        int changed=db.sql("""
            UPDATE readings SET glucose_mg_dl=:glucose,insulin_units=:insulin,moment=:moment,measured_at=:at,
            time_zone=:zone,local_date=:day,version=version+1,updated_at=now()
            WHERE user_id=:user AND id=:id AND version=:version
            """).param("glucose",input.glucoseMgDl()).param("insulin",input.insulinUnits()).param("moment",input.moment())
            .param("at",input.measuredAt().truncatedTo(ChronoUnit.MICROS)).param("zone",input.timeZone()).param("day",day).param("user",user)
            .param("id",id).param("version",input.version()).update();
        if(changed==0) throw ApiException.conflict("stale_version");
        badges.evaluate(user); return get(user,id);
    }
    @Transactional
    void delete(UUID user,UUID id,int version) {
        people.lock(user); get(user,id);
        if(db.sql("DELETE FROM readings WHERE user_id=:user AND id=:id AND version=:version")
            .param("user",user).param("id",id).param("version",version).update()==0) throw ApiException.conflict("stale_version");
    }
    record Filter(String sql,Map<String,Object> params) {}
    Filter filter(UUID user,LocalDate from,LocalDate to,String moment) {
        if(from!=null && to!=null && from.isAfter(to)) throw ApiException.invalid("invalid_date_range");
        if(moment!=null && !moment.matches(Models.MOMENTS)) throw ApiException.invalid("invalid_moment");
        var sql=new StringBuilder(" WHERE user_id=:user"); var params=new HashMap<String,Object>(); params.put("user",user);
        if(from!=null) { sql.append(" AND local_date>=:from"); params.put("from",from); }
        if(to!=null) { sql.append(" AND local_date<=:to"); params.put("to",to); }
        if(moment!=null) { sql.append(" AND moment=:moment"); params.put("moment",moment); }
        return new Filter(sql.toString(),params);
    }
    Page<Reading> list(UUID user,LocalDate from,LocalDate to,String moment,int limit,String cursor) {
        Paging.limit(limit); var filter=filter(user,from,to,moment); var position=Paging.decode(cursor);
        String sql="SELECT * FROM readings"+filter.sql();
        if(position!=null) {
            sql+=" AND (measured_at,id)<(:cursor_time,:cursor_id)";
            filter.params().put("cursor_time",position.time()); filter.params().put("cursor_id",position.id());
        }
        filter.params().put("limit",limit+1);
        var rows=db.sql(sql+" ORDER BY measured_at DESC,id DESC LIMIT :limit").params(filter.params()).query(Reading.class).list();
        return Paging.page(rows,limit,Reading::measuredAt,Reading::id);
    }
    ReadingSummary summary(UUID user,LocalDate from,LocalDate to,String moment) {
        var filter=filter(user,from,to,moment);
        long count=db.sql("SELECT count(*) FROM readings"+filter.sql()).params(filter.params()).query(Long.class).single();
        BigDecimal average=db.sql("SELECT round(avg(glucose_mg_dl),2) FROM readings"+filter.sql()).params(filter.params()).query(BigDecimal.class).optional().orElse(null);
        Reading latest=db.sql("SELECT * FROM readings"+filter.sql()+" ORDER BY measured_at DESC,id DESC LIMIT 1")
            .params(filter.params()).query(Reading.class).optional().orElse(null);
        return new ReadingSummary(count,average,latest);
    }
}
