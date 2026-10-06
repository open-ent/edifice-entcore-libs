package org.entcore.broker.api.dto.directory;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for responding with group information for multiple group IDs.
 */
public class GetGroupsByIdsResponseDTO {
    private final List<GroupDTO> groups;

    @JsonCreator
    public GetGroupsByIdsResponseDTO(@JsonProperty("groups") List<GroupDTO> groups) {
        this.groups = groups;
    }

    public List<GroupDTO> getGroups() {
        return groups;
    }

    @Override
    public String toString() {
        return "GetGroupsByIdsResponseDTO{" +
                "groups=" + groups +
                '}';
    }
}
