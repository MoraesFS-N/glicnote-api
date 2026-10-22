package com.glicnote.api;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.glicnote.api.Models.*;

@Service
public class FeedService {
    final JdbcClient db; final PeopleService people; final ReadingService readings; final BadgeService badges;
    FeedService(JdbcClient db,PeopleService people,ReadingService readings,BadgeService badges) {
        this.db=db; this.people=people; this.readings=readings; this.badges=badges;
    }
    private static final String ACCESS="""
        (p.author_id=:viewer OR EXISTS(SELECT 1 FROM follows f WHERE f.requester_id=:viewer AND f.target_id=p.author_id AND f.status='accepted'))
        AND NOT EXISTS(SELECT 1 FROM blocks b WHERE (b.blocker_id=:viewer AND b.blocked_id=p.author_id) OR (b.blocker_id=p.author_id AND b.blocked_id=:viewer))
        """;
    @Transactional
    FeedItem create(UUID user,PostInput input) {
        people.lock(user);
        boolean reading="reading".equals(input.type());
        if(reading) readings.get(user,input.referenceId()); else badges.earned(user,input.referenceId());
        var id=db.sql("""
            INSERT INTO posts(author_id,type,reading_id,earned_badge_id,caption) VALUES(:user,:type,:reading,:badge,:caption)
            ON CONFLICT DO NOTHING RETURNING id
            """).param("user",user).param("type",input.type()).param("reading",reading?input.referenceId():null)
            .param("badge",reading?null:input.referenceId()).param("caption",input.caption()).query(UUID.class).optional();
        UUID postId=id.orElseGet(() -> db.sql("SELECT id FROM posts WHERE author_id=:user AND "+(reading?"reading_id":"earned_badge_id")+"=:reference")
            .param("user",user).param("reference",input.referenceId()).query(UUID.class).single());
        return get(user,postId);
    }
    FeedItem get(UUID viewer,UUID id) {
        var post=db.sql("SELECT p.* FROM posts p WHERE p.id=:id AND "+ACCESS)
            .param("id",id).param("viewer",viewer).query(Post.class).optional().orElseThrow(ApiException::notFound);
        return view(viewer,post);
    }
    Page<FeedItem> list(UUID viewer,int limit,String cursor) {
        Paging.limit(limit); var position=Paging.decode(cursor);
        var params=new HashMap<String,Object>(); params.put("viewer",viewer); params.put("limit",limit+1);
        String sql="SELECT p.* FROM posts p WHERE "+ACCESS;
        if(position!=null) { sql+=" AND (p.created_at,p.id)<(:at,:id)"; params.put("at",position.time()); params.put("id",position.id()); }
        var rows=db.sql(sql+" ORDER BY p.created_at DESC,p.id DESC LIMIT :limit").params(params).query(Post.class).list();
        var page=Paging.page(rows,limit,Post::createdAt,Post::id);
        return new Page<>(page.items().stream().map(post -> view(viewer,post)).toList(),page.nextCursor());
    }
    private FeedItem view(UUID viewer,Post post) {
        return new FeedItem(post.id(),people.publicProfile(viewer,post.authorId()),post.type(),post.caption(),post.createdAt(),post.updatedAt(),
            post.readingId()==null?null:readings.get(post.authorId(),post.readingId()),
            post.earnedBadgeId()==null?null:badges.earned(post.authorId(),post.earnedBadgeId()));
    }
    @Transactional
    FeedItem update(UUID user,UUID id,CaptionInput input) {
        people.lock(user);
        int changed=db.sql("UPDATE posts SET caption=:caption,updated_at=now() WHERE id=:id AND author_id=:user")
            .param("caption",input.caption()).param("id",id).param("user",user).update();
        if(changed==0) throw ApiException.notFound();
        return get(user,id);
    }
    void delete(UUID user,UUID id) {
        if(db.sql("DELETE FROM posts WHERE id=:id AND author_id=:user").param("id",id).param("user",user).update()==0) throw ApiException.notFound();
    }
    Page<Notification> notifications(UUID user,int limit,String cursor) {
        Paging.limit(limit); var position=Paging.decode(cursor);
        var params=new HashMap<String,Object>(); params.put("user",user); params.put("limit",limit+1);
        String sql="SELECT * FROM notifications WHERE recipient_id=:user";
        if(position!=null) { sql+=" AND (created_at,id)<(:at,:id)"; params.put("at",position.time()); params.put("id",position.id()); }
        var rows=db.sql(sql+" ORDER BY created_at DESC,id DESC LIMIT :limit").params(params).query(Notification.class).list();
        return Paging.page(rows,limit,Notification::createdAt,Notification::id);
    }
    void readNotification(UUID user,UUID id) {
        if(db.sql("UPDATE notifications SET read_at=coalesce(read_at,now()) WHERE id=:id AND recipient_id=:user")
            .param("id",id).param("user",user).update()==0) throw ApiException.notFound();
    }
}
