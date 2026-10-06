package org.entcore.broker.proxy;

import io.vertx.core.Future;
import org.entcore.broker.api.BrokerListener;
import org.entcore.broker.api.dto.session.FindSessionRequestDTO;
import org.entcore.broker.api.dto.session.FindSessionResponseDTO;
import org.entcore.broker.api.dto.session.RefreshAllSessionsRequestDTO;
import org.entcore.broker.api.dto.session.RefreshAllSessionsResponseDTO;
import org.entcore.broker.api.dto.session.RefreshSessionRequestDTO;
import org.entcore.broker.api.dto.session.RefreshSessionResponseDTO;


/**
 * This interface defines the methods that will be used to listen to events from the session broker.
 */
public interface SessionBrokerListener {

  /**
   * This method is called when a session is requested.
   * It takes a FindSessionRequestDTO object as input and returns a FindSessionResponseDTO object.
   *
   * @param request The request object containing the session ID to be found.
   * @return The response object containing the session details.
   * @throw Exception If an error occurs while processing the request or session is not found.
   */
  @BrokerListener(subject = "session.find", proxy = true)
  Future<FindSessionResponseDTO> findSession(FindSessionRequestDTO request);

  /**
   * This method is called to refresh (recreate) a session.
   * It takes a RefreshSessionRequestDTO as input and returns a RefreshSessionResponseDTO.
   *
   * @param request The request containing the session info to refresh.
   * @return The response containing the new sessionId.
   */
  @BrokerListener(subject = "session.refresh", proxy = true)
  Future<RefreshSessionResponseDTO> refreshSession(RefreshSessionRequestDTO request);

  /**
   * This method is called to refresh (recreate) every active session of a given user, without needing to know
   * any of their sessionId in advance. Useful when the caller is not the target user (e.g. an admin granting
   * rights to another, already connected, user).
   *
   * @param request The request containing the userId whose sessions should be refreshed.
   * @return The response containing the ids of the refreshed sessions.
   */
  @BrokerListener(subject = "session.refreshAll", proxy = true)
  Future<RefreshAllSessionsResponseDTO> refreshAllSessions(RefreshAllSessionsRequestDTO request);
}
