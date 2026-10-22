package com.glicnote.api;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import static com.glicnote.api.Models.*;

final class Paging {
    record Cursor(OffsetDateTime time,UUID id) {}
    static int limit(int value) {
        if(value<1 || value>50) throw ApiException.invalid("invalid_page_size");
        return value;
    }
    static Cursor decode(String value) {
        if(value==null) return null;
        try {
            if(value.length()>200) throw new IllegalArgumentException();
            var parts=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=2) throw new IllegalArgumentException();
            return new Cursor(OffsetDateTime.parse(parts[0]),UUID.fromString(parts[1]));
        } catch(RuntimeException e) { throw ApiException.invalid("invalid_cursor"); }
    }
    static <T> Page<T> page(List<T> rows,int limit,Function<T,OffsetDateTime> time,Function<T,UUID> id) {
        var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
        String cursor=null;
        if(rows.size()>limit && !items.isEmpty()) {
            var last=items.get(items.size()-1);
            cursor=Base64.getUrlEncoder().withoutPadding().encodeToString((time.apply(last)+"|"+id.apply(last)).getBytes(StandardCharsets.UTF_8));
        }
        return new Page<>(items,cursor);
    }
}
