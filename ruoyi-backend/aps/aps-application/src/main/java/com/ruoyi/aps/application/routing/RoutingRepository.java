package com.ruoyi.aps.application.routing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.ruoyi.aps.application.routing.RoutingCatalog.Item;
import com.ruoyi.aps.application.routing.RoutingCatalog.OperationSpec;
import com.ruoyi.aps.application.routing.RoutingCatalog.ResourceRequirement;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteEdge;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteGraph;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteNode;
import com.ruoyi.aps.application.routing.RoutingCatalog.RouteVersion;

public interface RoutingRepository
{
    List<Item> listItems();
    Optional<Item> findItem(String id);
    void insertItem(Item value, String actor);
    int updateItem(Item value, String actor);

    List<OperationSpec> listOperations();
    Optional<OperationSpec> findOperation(String id);
    void insertOperation(OperationSpec value, String actor);
    int updateOperation(OperationSpec value, String actor);
    void replaceOperationChildren(OperationSpec value, String actor);
    boolean requirementTargetExists(ResourceRequirement requirement);

    List<RouteVersion> listRoutes(String itemId);
    Optional<RouteVersion> findRoute(String id);
    RouteGraph getRouteGraph(String id);
    void insertRoute(RouteVersion value, String actor);
    int updateRouteStatus(String id, long rowVersion, String status, String actor, Instant approvedAt);
    default int advanceDraftRouteRevision(String id,long rowVersion,String actor) { throw new UnsupportedOperationException("路线草稿版本更新尚未接入"); }
    void replaceRouteGraph(String routeVersionId, List<RouteNode> nodes, List<RouteEdge> edges, String actor);
}
