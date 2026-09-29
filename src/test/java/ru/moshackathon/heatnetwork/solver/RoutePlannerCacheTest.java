package ru.moshackathon.heatnetwork.solver;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import ru.moshackathon.heatnetwork.model.InputFeature;
import ru.moshackathon.heatnetwork.model.ProblemData;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutePlannerCacheTest {
    @Test
    void cacheSeparatesDiameterAndRouteRole() {
        RoutePlanner planner = new RoutePlanner();
        RoutePlanner.RoutingContext context = planner.prepare(
                new ProblemData(Collections.emptyList(), Collections.emptyList()));
        Coordinate start = new Coordinate(0.0, 0.0);
        Coordinate end = new Coordinate(10.0, 0.0);
        RoutePlanner.RouteRequest branch50 = new RoutePlanner.RouteRequest(
                50, RoutePlanner.RouteRole.PLANNED_BRANCH);

        assertTrue(planner.plan(start, end, context, RoutePlanner.RouteMode.CORRIDOR, branch50).isPresent());
        assertTrue(planner.plan(start, end, context, RoutePlanner.RouteMode.CORRIDOR, branch50).isPresent());
        assertTrue(planner.plan(start, end, context, RoutePlanner.RouteMode.CORRIDOR,
                new RoutePlanner.RouteRequest(100, RoutePlanner.RouteRole.PLANNED_BRANCH)).isPresent());
        assertTrue(planner.plan(start, end, context, RoutePlanner.RouteMode.CORRIDOR,
                new RoutePlanner.RouteRequest(50, RoutePlanner.RouteRole.EXISTING_TIE_IN)).isPresent());

        assertEquals("route-cache-v9-clarified-exterior-entry hits=1, misses=3, unique=3, timeout_aborted=0",
                context.describeCache());
        assertEquals("attempts=3, successes=3, direct_successes=3, missing_endpoint_approach=0, "
                        + "core_failures=0, combined_geometry_rejections=0, combined_turn_rejections=0, "
                        + "endpoints_inside_oks=0, endpoints_outside_oks=0, containing_oks_matches=0, "
                        + "boundary_candidates=0, endpoint_portals_created=0, "
                        + "portal_own_footprint_rejections=0, portal_single_exit_rejections=0, "
                        + "portal_restriction_rejections=0, entry_cache_hits=0, entry_cache_misses=0, "
                        + "entry_interval_rejections=0",
                context.describeRoutingAttempts());
    }

    @Test
    void documentedRulesetRejectsFarSideEntryButExperimentalRulesetAllowsIt() {
        GeometryFactory factory = new GeometryFactory();
        Polygon footprint = factory.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(10, 0),
                new Coordinate(10, 10), new Coordinate(0, 10),
                new Coordinate(0, 0),
        });
        Map<String, Object> properties = new HashMap<>();
        properties.put("restriction_type", "oks");
        InputFeature restriction = new InputFeature(
                "oks", "restriction", properties, footprint, footprint);
        ProblemData data = new ProblemData(
                Collections.singletonList(restriction), Collections.emptyList());
        RoutePlanner planner = new RoutePlanner();
        RoutePlanner.RoutingContext documented = planner.prepare(
                data, true, () -> true, RoutePlanner.EntryStrategy.DIRECT_ALLOWED,
                RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1);
        RoutePlanner.RoutingContext clarified = planner.prepare(
                data, true, () -> true, RoutePlanner.EntryStrategy.DIRECT_ALLOWED,
                RoutePlanner.RuleSet.CLARIFIED_EXTERIOR_BOUNDARY_V1);
        RoutePlanner.RoutingContext experimental = planner.prepare(
                data, true, () -> true, RoutePlanner.EntryStrategy.DIRECT_ALLOWED,
                RoutePlanner.RuleSet.EXPERIMENTAL_ANY_BOUNDARY_V1);
        LineString farSide = factory.createLineString(new Coordinate[]{
                new Coordinate(1, 5), new Coordinate(16, 5),
        });
        LineString nearestSide = factory.createLineString(new Coordinate[]{
                new Coordinate(1, 5), new Coordinate(-6, 5),
        });

        assertFalse(planner.avoidsForbiddenRestrictions(farSide, documented));
        assertTrue(planner.avoidsForbiddenRestrictions(farSide, clarified));
        assertTrue(planner.avoidsForbiddenRestrictions(farSide, experimental));
        assertTrue(planner.avoidsForbiddenRestrictions(nearestSide, documented));
    }

    @Test
    void clarifiedRulesetRejectsEntryThroughCourtyardHole() {
        GeometryFactory factory = new GeometryFactory();
        LinearRing shell = factory.createLinearRing(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(50, 0), new Coordinate(50, 50),
                new Coordinate(0, 50), new Coordinate(0, 0)});
        LinearRing hole = factory.createLinearRing(new Coordinate[]{
                new Coordinate(10, 10), new Coordinate(40, 10), new Coordinate(40, 40),
                new Coordinate(10, 40), new Coordinate(10, 10)});
        Polygon footprint = factory.createPolygon(shell, new LinearRing[]{hole});
        Map<String, Object> properties = new HashMap<>();
        properties.put("restriction_type", "oks");
        InputFeature restriction = new InputFeature(
                "oks", "restriction", properties, footprint, footprint);
        ProblemData data = new ProblemData(
                Collections.singletonList(restriction), Collections.emptyList());
        RoutePlanner planner = new RoutePlanner();
        RoutePlanner.RoutingContext clarified = planner.prepare(
                data, true, () -> true, RoutePlanner.EntryStrategy.DIRECT_ALLOWED,
                RoutePlanner.RuleSet.CLARIFIED_EXTERIOR_BOUNDARY_V1);
        LineString courtyardEntry = factory.createLineString(new Coordinate[]{
                new Coordinate(9, 25), new Coordinate(20, 25)});

        assertFalse(planner.avoidsForbiddenRestrictions(courtyardEntry, clarified));
    }

    @Test
    void clarifiedRulesetCanUseFartherExteriorWhenNearestRayIsBlocked() {
        GeometryFactory factory = new GeometryFactory();
        Polygon footprint = factory.createPolygon(new Coordinate[]{
                new Coordinate(0, 0), new Coordinate(20, 0), new Coordinate(20, 20),
                new Coordinate(12, 20), new Coordinate(12, 8), new Coordinate(8, 8),
                new Coordinate(8, 20), new Coordinate(0, 20), new Coordinate(0, 0)});
        Map<String, Object> properties = new HashMap<>();
        properties.put("restriction_type", "oks");
        InputFeature restriction = new InputFeature(
                "oks", "restriction", properties, footprint, footprint);
        ProblemData data = new ProblemData(
                Collections.singletonList(restriction), Collections.emptyList());
        RoutePlanner planner = new RoutePlanner();
        Coordinate target = new Coordinate(7, 7);
        Coordinate root = new Coordinate(-20, 7);
        RoutePlanner.RouteRequest request = new RoutePlanner.RouteRequest(
                100, RoutePlanner.RouteRole.EXISTING_TIE_IN);
        RoutePlanner.RoutingContext oldRules = planner.prepare(
                data, true, () -> true, RoutePlanner.EntryStrategy.PORTAL_ONLY,
                RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1);
        RoutePlanner.RoutingContext clarified = planner.prepare(
                data, true, () -> true, RoutePlanner.EntryStrategy.PORTAL_ONLY,
                RoutePlanner.RuleSet.CLARIFIED_EXTERIOR_BOUNDARY_V1);

        assertFalse(planner.plan(target, root, oldRules, RoutePlanner.RouteMode.CORRIDOR,
                request).isPresent());
        assertTrue(planner.plan(target, root, clarified, RoutePlanner.RouteMode.CORRIDOR,
                request).isPresent());
    }

    @Test
    void railwayLineIsForbiddenEvenAtAValidCrossingAngle() {
        GeometryFactory factory = new GeometryFactory();
        LineString railway = factory.createLineString(new Coordinate[]{
                new Coordinate(0, -20), new Coordinate(0, 20)});
        Map<String, Object> properties = new HashMap<>();
        properties.put("restriction_type", "railway");
        InputFeature restriction = new InputFeature(
                "railway", "restriction", properties, railway, railway);
        RoutePlanner planner = new RoutePlanner();
        RoutePlanner.RoutingContext context = planner.prepare(
                new ProblemData(Collections.singletonList(restriction), Collections.emptyList()));
        LineString crossing = factory.createLineString(new Coordinate[]{
                new Coordinate(-10, 0), new Coordinate(10, 0)});

        assertFalse(planner.avoidsForbiddenRestrictions(crossing, context, 65));
    }

    @Test
    void timeoutAbortedEmptyRouteIsNotCachedAsImpossible() {
        RoutePlanner planner = new RoutePlanner();
        AtomicInteger budgetChecks = new AtomicInteger();
        RoutePlanner.RoutingContext context = planner.prepare(
                new ProblemData(Collections.emptyList(), Collections.emptyList()),
                true,
                () -> {
                    int check = budgetChecks.incrementAndGet();
                    return check != 2 && check != 3;
                },
                RoutePlanner.EntryStrategy.PORTAL_ONLY,
                RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1);
        Coordinate start = new Coordinate(0.0, 0.0);
        Coordinate end = new Coordinate(10.0, 0.0);

        assertFalse(planner.plan(start, end, context).isPresent());
        assertTrue(planner.plan(start, end, context).isPresent());
        assertEquals("route-cache-v9-clarified-exterior-entry hits=0, misses=2, unique=1, timeout_aborted=1",
                context.describeCache());
    }

    @Test
    void existingHeatNetworkIsARestrictionExceptForFinalTieInPrimitive() {
        GeometryFactory factory = new GeometryFactory();
        LineString existingLine = factory.createLineString(new Coordinate[]{
                new Coordinate(10, -10), new Coordinate(10, 10),
        });
        Map<String, Object> properties = new HashMap<>();
        properties.put("diameter", 400);
        InputFeature existingNetwork = new InputFeature(
                "existing", "heat_network", properties, existingLine, existingLine);
        RoutePlanner planner = new RoutePlanner();
        RoutePlanner.RoutingContext context = planner.prepare(new ProblemData(
                Collections.singletonList(existingNetwork), Collections.emptyList()));
        LineString parallelTooClose = factory.createLineString(new Coordinate[]{
                new Coordinate(8.5, -8), new Coordinate(8.5, 8),
        });

        assertFalse(planner.avoidsForbiddenRestrictions(parallelTooClose, context, 200));
        assertTrue(planner.plan(new Coordinate(0, 0), new Coordinate(10, 0), context,
                RoutePlanner.RouteMode.CORRIDOR,
                new RoutePlanner.RouteRequest(200, RoutePlanner.RouteRole.EXISTING_TIE_IN)).isPresent());
    }

    @Test
    void checksContinuousDiameterClearanceAlongMiddleOfLine() {
        GeometryFactory factory = new GeometryFactory();
        Polygon footprint = factory.createPolygon(new Coordinate[]{
                new Coordinate(-1, 1), new Coordinate(1, 1),
                new Coordinate(1, 2), new Coordinate(-1, 2),
                new Coordinate(-1, 1),
        });
        Map<String, Object> properties = new HashMap<>();
        properties.put("restriction_type", "oks");
        InputFeature restriction = new InputFeature(
                "middle", "restriction", properties, footprint, footprint);
        RoutePlanner planner = new RoutePlanner();
        RoutePlanner.RoutingContext context = planner.prepare(
                new ProblemData(Collections.singletonList(restriction), Collections.emptyList()));
        LineString tooClose = factory.createLineString(new Coordinate[]{
                new Coordinate(-10, -4.18), new Coordinate(10, -4.18),
        });
        LineString clear = factory.createLineString(new Coordinate[]{
                new Coordinate(-10, -4.22), new Coordinate(10, -4.22),
        });

        assertFalse(planner.avoidsForbiddenRestrictions(tooClose, context));
        assertTrue(planner.avoidsForbiddenRestrictions(clear, context));
    }

    @Test
    void endpointAnalysisIsReusedAndDiameterInvalidatesIt() {
        GeometryFactory factory = new GeometryFactory();
        Polygon footprint = factory.createPolygon(
                factory.createLinearRing(new Coordinate[]{
                        new Coordinate(0, 0), new Coordinate(20, 0),
                        new Coordinate(20, 20), new Coordinate(0, 20),
                        new Coordinate(0, 0),
                }),
                new org.locationtech.jts.geom.LinearRing[]{factory.createLinearRing(new Coordinate[]{
                        new Coordinate(8, 8), new Coordinate(12, 8),
                        new Coordinate(12, 12), new Coordinate(8, 12),
                        new Coordinate(8, 8),
                })});
        Map<String, Object> properties = new HashMap<>();
        properties.put("restriction_type", "oks");
        InputFeature restriction = new InputFeature(
                "owner", "restriction", properties, footprint, footprint);
        RoutePlanner planner = new RoutePlanner();
        RoutePlanner.RoutingContext context = planner.prepare(
                new ProblemData(Collections.singletonList(restriction), Collections.emptyList()),
                true, () -> true, RoutePlanner.EntryStrategy.PORTAL_ONLY,
                RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1);
        Coordinate start = new Coordinate(7, 10);

        assertFalse(planner.plan(start, new Coordinate(30, 10), context,
                RoutePlanner.RouteMode.CORRIDOR,
                new RoutePlanner.RouteRequest(100, RoutePlanner.RouteRole.EXISTING_TIE_IN)).isPresent());
        assertFalse(planner.plan(start, new Coordinate(30, 11), context,
                RoutePlanner.RouteMode.CORRIDOR,
                new RoutePlanner.RouteRequest(100, RoutePlanner.RouteRole.EXISTING_TIE_IN)).isPresent());
        assertTrue(context.describeRoutingAttempts().contains("entry_cache_hits=1"));
        assertTrue(context.describeRoutingAttempts().contains("entry_cache_misses=3"));
        assertTrue(context.describeRoutingAttempts().contains("entry_interval_rejections=1"));

        assertFalse(planner.plan(start, new Coordinate(30, 12), context,
                RoutePlanner.RouteMode.CORRIDOR,
                new RoutePlanner.RouteRequest(125, RoutePlanner.RouteRole.EXISTING_TIE_IN)).isPresent());
        assertTrue(context.describeRoutingAttempts().contains("entry_interval_rejections=2"));
    }

    @Test
    void oneBlockedEqualNearestRayDoesNotRejectTheOpenRay() {
        GeometryFactory factory = new GeometryFactory();
        Polygon footprint = factory.createPolygon(
                factory.createLinearRing(new Coordinate[]{
                        new Coordinate(0, 0), new Coordinate(20, 0),
                        new Coordinate(20, 20), new Coordinate(0, 20),
                        new Coordinate(0, 0),
                }),
                new org.locationtech.jts.geom.LinearRing[]{factory.createLinearRing(new Coordinate[]{
                        new Coordinate(10, 8), new Coordinate(14, 8),
                        new Coordinate(14, 12), new Coordinate(10, 12),
                        new Coordinate(10, 8),
                })});
        Map<String, Object> properties = new HashMap<>();
        properties.put("restriction_type", "oks");
        InputFeature restriction = new InputFeature(
                "owner", "restriction", properties, footprint, footprint);
        RoutePlanner planner = new RoutePlanner();
        RoutePlanner.RoutingContext context = planner.prepare(
                new ProblemData(Collections.singletonList(restriction), Collections.emptyList()),
                true, () -> true, RoutePlanner.EntryStrategy.PORTAL_ONLY,
                RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1);

        assertTrue(planner.plan(new Coordinate(5, 10), new Coordinate(-20, 10), context,
                RoutePlanner.RouteMode.CORRIDOR,
                new RoutePlanner.RouteRequest(100, RoutePlanner.RouteRole.EXISTING_TIE_IN)).isPresent());
        assertTrue(context.describeEntryWitnesses().contains("nearest_ray=1/2"));
        assertTrue(context.describeEntryWitnesses().contains("nearest_ray=2/2"));
        assertTrue(context.describeRoutingAttempts().contains("entry_interval_rejections=0"));
    }

    @Test
    void existingTieInInsideFirstFreePocketIsAllowedButTransitIsNot() {
        GeometryFactory factory = new GeometryFactory();
        Polygon footprint = factory.createPolygon(
                factory.createLinearRing(new Coordinate[]{
                        new Coordinate(0, 0), new Coordinate(20, 0),
                        new Coordinate(20, 20), new Coordinate(0, 20),
                        new Coordinate(0, 0),
                }),
                new org.locationtech.jts.geom.LinearRing[]{factory.createLinearRing(new Coordinate[]{
                        new Coordinate(8, 8), new Coordinate(12, 8),
                        new Coordinate(12, 12), new Coordinate(8, 12),
                        new Coordinate(8, 8),
                })});
        Map<String, Object> restrictionProperties = new HashMap<>();
        restrictionProperties.put("restriction_type", "oks");
        InputFeature restriction = new InputFeature(
                "owner", "restriction", restrictionProperties, footprint, footprint);
        Map<String, Object> networkProperties = new HashMap<>();
        networkProperties.put("diameter", 400);
        LineString pocketNetwork = factory.createLineString(new Coordinate[]{
                new Coordinate(10, 9), new Coordinate(10, 11),
        });
        InputFeature network = new InputFeature(
                "pocket", "heat_network", networkProperties, pocketNetwork, pocketNetwork);
        RoutePlanner planner = new RoutePlanner();
        RoutePlanner.RoutingContext context = planner.prepare(
                new ProblemData(java.util.Arrays.asList(restriction, network), Collections.emptyList()),
                true, () -> true, RoutePlanner.EntryStrategy.DIRECT_ALLOWED,
                RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1);

        assertTrue(planner.plan(new Coordinate(7, 10), new Coordinate(10, 10), context,
                RoutePlanner.RouteMode.CORRIDOR,
                new RoutePlanner.RouteRequest(100, RoutePlanner.RouteRole.EXISTING_TIE_IN)).isPresent());

        LineString farNetwork = factory.createLineString(new Coordinate[]{
                new Coordinate(21, 9), new Coordinate(21, 11),
        });
        InputFeature far = new InputFeature(
                "far", "heat_network", networkProperties, farNetwork, farNetwork);
        RoutePlanner.RoutingContext blockedContext = planner.prepare(
                new ProblemData(java.util.Arrays.asList(restriction, far), Collections.emptyList()),
                true, () -> true, RoutePlanner.EntryStrategy.DIRECT_ALLOWED,
                RoutePlanner.RuleSet.DOCUMENT_NEAREST_V1);
        assertFalse(planner.plan(new Coordinate(7, 10), new Coordinate(21, 10), blockedContext,
                RoutePlanner.RouteMode.CORRIDOR,
                new RoutePlanner.RouteRequest(100, RoutePlanner.RouteRole.EXISTING_TIE_IN)).isPresent());
    }
}
