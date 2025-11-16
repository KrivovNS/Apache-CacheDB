package com.mipt.controller.Handler;

import com.mipt.controller.DataType;
import com.mipt.controller.UserRole;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

public class InfoPageHandler extends BaseNettyHandler {

  public void sendInfoPage(ChannelHandlerContext ctx) {
    String info = buildInfoContent();
    FullHttpResponse response = createResponse(HttpResponseStatus.OK, info);
    ctx.writeAndFlush(response);
  }

  private String buildInfoContent() {
    return "Cache Storage HTTP Server\n\n" +
        "Available endpoints:\n" +
        "CACHE OPERATIONS:\n" +
        "GET    /cache?key=KEY&type=TYPE&login=USERNAME&password=PASSWORD\n" +
        "POST   /cache?key=KEY&type=TYPE&login=USERNAME&password=PASSWORD (with body in request)\n" +
        "PUT    /cache?key=KEY&type=TYPE&login=USERNAME&password=PASSWORD (with body in request)\n" +
        "DELETE /cache?key=KEY&type=TYPE&login=USERNAME&password=PASSWORD\n\n" +
        "STORAGE OPERATIONS:\n" +
        "POST   /storage?login=USERNAME&password=PASSWORD (initialize storage)\n" +
        "GET    /storage?login=USERNAME&password=PASSWORD (get storage statistics)\n\n" +
        "Data types: " + String.join(", ", DataType.getAllValues()) + "\n" +
        "User roles: " + String.join(", ", UserRole.getAllValues()) + "\n\n" +
        "Examples:\n" +
        "GET: curl \"http://localhost:8080/cache?key=test&type=string&login=admin&password=pass\"\n" +
        "POST: curl -X POST -d 'your_data_here' \"http://localhost:8080/cache?key=test&type=string&login=admin&password=pass\"\n" +
        "PUT: curl -X PUT -d 'your_data_here' \"http://localhost:8080/cache?key=test&type=string&login=admin&password=pass\"\n" +
        "Stats: curl \"http://localhost:8080/storage?login=admin&password=pass\"";
  }
}