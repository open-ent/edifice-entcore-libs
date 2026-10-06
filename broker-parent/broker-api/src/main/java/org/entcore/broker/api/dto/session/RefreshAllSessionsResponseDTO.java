package org.entcore.broker.api.dto.session;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * DTO for the response of a refresh-all-sessions request.
 */
public class RefreshAllSessionsResponseDTO {
    private final List<String> sessionIds;

    /**
     * Constructor for RefreshAllSessionsResponseDTO.
     * @param sessionIds The ids of the sessions that were refreshed.
     */
    @JsonCreator
    public RefreshAllSessionsResponseDTO(@JsonProperty("sessionIds") List<String> sessionIds) {
        this.sessionIds = sessionIds;
    }

    /**
     * @return The ids of the refreshed sessions.
     */
    public List<String> getSessionIds() { return sessionIds; }

    @Override
    public String toString() {
        return "RefreshAllSessionsResponseDTO{" +
                "sessionIds=" + sessionIds +
                '}';
    }
}
