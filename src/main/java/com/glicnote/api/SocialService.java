package com.glicnote.api;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.glicnote.api.Models.*;

@Service
public class SocialService {
    final JdbcClient db; final PeopleService people;
    SocialService(JdbcClient db,PeopleService people) { this.db=db; this.people=people; }
    private void pair(UUID a,UUID b) {
        if(a.equals(b)) throw ApiException.invalid("self_relationship");
        var ids=db.sql("SELECT id FROM profiles WHERE id IN (:a,:b) ORDER BY id FOR UPDATE")
            .param("a",a).param("b",b).query(UUID.class).list();
        if(ids.size()!=2) throw ApiException.notFound();
    }
    private Follow relationship(UUID id) {
        return db.sql("SELECT * FROM follows WHERE id=:id").param("id",id).query(Follow.class).optional().orElseThrow(ApiException::notFound);
    }
    @Transactional
    Follow request(UUID requester,UUID target) {
        pair(requester,target);
        if(people.blocked(requester,target)) throw ApiException.notFound();
        var existing=db.sql("SELECT * FROM follows WHERE requester_id=:a AND target_id=:b")
            .param("a",requester).param("b",target).query(Follow.class).optional();
        Follow result;
        if(existing.isPresent()) {
            var old=existing.get();
            if("pending".equals(old.status())) return old;
            if("accepted".equals(old.status())) throw ApiException.conflict("already_following");
            result=db.sql("""
                UPDATE follows SET status='pending',version=version+1,requested_at=now(),responded_at=NULL,ended_at=NULL WHERE id=:id RETURNING *
                """).param("id",old.id()).query(Follow.class).single();
        } else {
            result=db.sql("INSERT INTO follows(requester_id,target_id,status) VALUES(:a,:b,'pending') RETURNING *")
                .param("a",requester).param("b",target).query(Follow.class).single();
        }
        notifyFollow(result,target,requester,"follow_requested"); return result;
    }
    @Transactional
    Follow decide(UUID user,UUID id,Decision decision) {
        var before=relationship(id);
        if(!before.targetId().equals(user)) throw ApiException.notFound();
        pair(before.requesterId(),before.targetId());
        if(people.blocked(before.requesterId(),before.targetId())) throw ApiException.notFound();
        var current=relationship(id);
        pending(current,decision.version());
        var result=db.sql("UPDATE follows SET status=:status,version=version+1,responded_at=now() WHERE id=:id RETURNING *")
            .param("status",decision.accept()?"accepted":"declined").param("id",id).query(Follow.class).single();
        if(decision.accept()) notifyFollow(result,result.requesterId(),user,"follow_accepted");
        return result;
    }
    @Transactional
    Follow cancel(UUID user,UUID id,int version) {
        var before=relationship(id);
        if(!before.requesterId().equals(user)) throw ApiException.notFound();
        pair(before.requesterId(),before.targetId());
        var current=relationship(id); pending(current,version);
        return db.sql("UPDATE follows SET status='cancelled',version=version+1,ended_at=now() WHERE id=:id RETURNING *")
            .param("id",id).query(Follow.class).single();
    }
    @Transactional
    void end(UUID user,UUID id,int version) {
        var before=relationship(id);
        if(!before.requesterId().equals(user) && !before.targetId().equals(user)) throw ApiException.notFound();
        pair(before.requesterId(),before.targetId());
        var current=relationship(id);
        if(current.version()!=version) throw ApiException.conflict("stale_version");
        if(!"accepted".equals(current.status())) throw ApiException.conflict("relationship_not_accepted");
        db.sql("UPDATE follows SET status='ended',version=version+1,ended_at=now() WHERE id=:id").param("id",id).update();
    }
    private void pending(Follow follow,int version) {
        if(follow.version()!=version) throw ApiException.conflict("stale_version");
        if(!"pending".equals(follow.status())) throw ApiException.conflict("request_not_pending");
    }
    private void notifyFollow(Follow follow,UUID recipient,UUID actor,String type) {
        db.sql("""
            INSERT INTO notifications(recipient_id,actor_id,type,follow_id,request_version,event_key)
            VALUES(:recipient,:actor,:type,:follow,:version,:key) ON CONFLICT(event_key) DO NOTHING
            """).param("recipient",recipient).param("actor",actor).param("type",type).param("follow",follow.id())
            .param("version",follow.version()).param("key",type+":"+follow.id()+":"+follow.version()+":"+recipient).update();
    }
    Page<FollowView> list(UUID user,String direction,String status,int limit,String cursor) {
        if(!Set.of("incoming","outgoing").contains(direction)) throw ApiException.invalid("invalid_direction");
        if(!Set.of("pending","accepted","declined","cancelled","ended").contains(status)) throw ApiException.invalid("invalid_status");
        Paging.limit(limit); var position=Paging.decode(cursor);
        var params=new HashMap<String,Object>(); params.put("user",user); params.put("status",status); params.put("limit",limit+1);
        String sql="SELECT * FROM follows f WHERE "+("incoming".equals(direction)?"target_id":"requester_id")+"=:user AND status=:status";
        sql+=" AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker_id=f.requester_id AND b.blocked_id=f.target_id) OR (b.blocker_id=f.target_id AND b.blocked_id=f.requester_id))";
        if(position!=null) { sql+=" AND (requested_at,id)<(:at,:id)"; params.put("at",position.time()); params.put("id",position.id()); }
        var rows=db.sql(sql+" ORDER BY requested_at DESC,id DESC LIMIT :limit").params(params).query(Follow.class).list();
        var page=Paging.page(rows,limit,Follow::requestedAt,Follow::id);
        return new Page<>(page.items().stream().map(follow -> new FollowView(follow,people.publicProfile(user,follow.requesterId()),people.publicProfile(user,follow.targetId()))).toList(),page.nextCursor());
    }
    @Transactional
    void block(UUID user,UUID target) {
        pair(user,target);
        db.sql("INSERT INTO blocks(blocker_id,blocked_id) VALUES(:a,:b) ON CONFLICT DO NOTHING").param("a",user).param("b",target).update();
        db.sql("""
            UPDATE follows SET status=CASE WHEN status='pending' THEN 'cancelled' ELSE 'ended' END,ended_at=now(),version=version+1
            WHERE ((requester_id=:a AND target_id=:b) OR (requester_id=:b AND target_id=:a)) AND status IN ('pending','accepted')
            """).param("a",user).param("b",target).update();
    }
    @Transactional
    void unblock(UUID user,UUID target) {
        pair(user,target);
        db.sql("DELETE FROM blocks WHERE blocker_id=:a AND blocked_id=:b").param("a",user).param("b",target).update();
    }
    List<Block> blocks(UUID user,int limit) {
        return db.sql("SELECT blocked_id,created_at FROM blocks WHERE blocker_id=:user ORDER BY created_at DESC,blocked_id LIMIT :limit")
            .param("user",user).param("limit",Paging.limit(limit)).query(Block.class).list();
    }
}
