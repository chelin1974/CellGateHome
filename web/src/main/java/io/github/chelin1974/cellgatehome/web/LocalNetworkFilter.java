package io.github.chelin1974.cellgatehome.web;

import io.github.chelin1974.cellgatehome.web.DashboardServer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers only devices on the home network, like the bridge's own HTTP server does.
 *
 * <p>The dashboard can switch circuits in your house. It binds the host's network because
 * HomeKit needs that, so it is reachable from anywhere on the LAN - and would be reachable
 * from the internet if a router ever forwarded the port by mistake. This refuses anything
 * that is not a private, link-local or loopback address, so a misconfigured router is not
 * enough on its own to expose it.
 *
 * <p>The check is {@link DashboardServer#isLocal}, shared with the CLI flavour rather than
 * reimplemented, so the two cannot disagree about what counts as local.
 *
 * <h2>What is exempt</h2>
 *
 * {@code /healthz} and {@code /actuator/**} are answered before this check. They are a
 * supervisor's endpoints, and a probe that got a 403 would fail forever and restart the
 * container in a loop - worse than disclosing a status word, which is all they return.
 * Kubernetes probes arrive from the node, whose address is usually private and would pass
 * anyway, but a cluster on CGNAT space (100.64/10) would not, and that trap is not worth
 * leaving in place.
 */
@Component
@Order(LocalNetworkFilter.ORDER)
public class LocalNetworkFilter extends OncePerRequestFilter {

  /** Ahead of the controllers, behind nothing that matters. */
  static final int ORDER = 0;

  private static final Logger log = LoggerFactory.getLogger(LocalNetworkFilter.class);

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.equals("/healthz") || path.startsWith("/actuator");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    if (isLocal(request.getRemoteAddr())) {
      chain.doFilter(request, response);
      return;
    }

    log.warn("refused a dashboard request from {}", request.getRemoteAddr());
    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response
        .getWriter()
        .write("{\"error\":\"This dashboard only answers devices on the home network.\"}");
  }

  private static boolean isLocal(String remoteAddress) {
    if (remoteAddress == null || remoteAddress.isBlank()) {
      return false;
    }
    try {
      // getByName on a literal address does not resolve anything, so this cannot block.
      return DashboardServer.isLocal(InetAddress.getByName(remoteAddress));
    } catch (UnknownHostException e) {
      return false;
    }
  }
}
