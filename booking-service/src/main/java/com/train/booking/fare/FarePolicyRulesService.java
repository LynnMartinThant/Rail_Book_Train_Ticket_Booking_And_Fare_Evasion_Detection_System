package com.train.booking.fare;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UK-style fare rule composition for explanations and enforcement amounts:
 * (1) base fare from route table, (2) time-band adjustment, (3) overtravel delta,
 * (4) missing-ticket penalty floor vs 2× single, (5) confidence gating documented at enforcement time.
 */
@Service
public class FarePolicyRulesService {

    @Value("${booking.fare.timezone:Europe/London}")
    private String fareTimezone;

    @Value("${booking.fare.weekday-afternoon-offpeak-discount-percent:15}")
    private int weekdayAfternoonOffpeakDiscountPercent;

    @Value("${booking.fare.weekend-discount-percent:15}")
    private int weekendDiscountPercent;

    @Value("${booking.fare.peak-surcharge-percent:15}")
    private int peakSurchargePercent;

    @Value("${booking.fare.weekday-offpeak-start-hour:13}")
    private int weekdayOffpeakStartHour;

    @Value("${booking.fare.weekday-offpeak-end-hour:17}")
    private int weekdayOffpeakEndHour;

    @Value("${booking.fare.peak-morning-start-hour:7}")
    private int peakMorningStartHour;

    @Value("${booking.fare.peak-morning-end-hour:10}")
    private int peakMorningEndHour;

    @Value("${booking.fare.peak-evening-start-hour:16}")
    private int peakEveningStartHour;

    @Value("${booking.fare.peak-evening-end-hour:19}")
    private int peakEveningEndHour;

    /**
     * Rule 4: MAX(configured penalty fare, 2 × standard single for the travelled route).
     */
    public BigDecimal computeNoTicketPenalty(BigDecimal routeSingleFare, BigDecimal configuredPenaltyFare) {
        BigDecimal penalty = configuredPenaltyFare != null ? configuredPenaltyFare : BigDecimal.ZERO;
        if (routeSingleFare == null || routeSingleFare.compareTo(BigDecimal.ZERO) <= 0) {
            return penalty.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal twoX = routeSingleFare.multiply(BigDecimal.valueOf(2)).setScale(2, RoundingMode.HALF_UP);
        return penalty.max(twoX).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Rule 3: fare(actual journey) − fare(entitled journey) when both legs exist in the fare table.
     */
    public BigDecimal computeOvertravelAdjustment(BigDecimal actualJourneyFare, BigDecimal entitledJourneyFare) {
        if (actualJourneyFare == null) return BigDecimal.ZERO;
        if (entitledJourneyFare == null || entitledJourneyFare.compareTo(BigDecimal.ZERO) <= 0) {
            return actualJourneyFare.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }
        return actualJourneyFare.subtract(entitledJourneyFare).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    public Map<String, Object> timeBandBreakdown(BigDecimal baseFare, Instant travelInstant) {
        Map<String, Object> rule2 = new LinkedHashMap<>();
        rule2.put("rule", "RULE_2_TIME_VALIDITY");
        if (baseFare == null || travelInstant == null) {
            rule2.put("timeBand", "UNKNOWN");
            rule2.put("timeAdjustment", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            rule2.put("summary", "No travel instant or base fare; time adjustment not applied.");
            return rule2;
        }
        ZoneId zone = ZoneId.of(fareTimezone);
        ZonedDateTime z = travelInstant.atZone(zone);
        int hour = z.getHour();
        DayOfWeek dow = z.getDayOfWeek();
        boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;

        String band;
        BigDecimal adjustment;
        if (weekend) {
            band = "WEEKEND_OFF_PEAK";
            adjustment = percentOfBase(baseFare, -weekendDiscountPercent);
        } else if (hour >= weekdayOffpeakStartHour && hour < weekdayOffpeakEndHour) {
            band = "WEEKDAY_AFTERNOON_OFF_PEAK";
            adjustment = percentOfBase(baseFare, -weekdayAfternoonOffpeakDiscountPercent);
        } else if ((hour >= peakMorningStartHour && hour < peakMorningEndHour)
            || (hour >= peakEveningStartHour && hour < peakEveningEndHour)) {
            band = "PEAK";
            adjustment = percentOfBase(baseFare, peakSurchargePercent);
        } else {
            band = "INTER_PEAK_WEEKDAY";
            adjustment = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }

        rule2.put("timeBand", band);
        rule2.put("timezone", fareTimezone);
        rule2.put("localDateTime", z.toLocalDateTime().toString());
        rule2.put("timeAdjustment", adjustment);
        rule2.put("summary", adjustment.compareTo(BigDecimal.ZERO) > 0
            ? "Travel in peak band → positive time adjustment (surrogate for peak fare difference)."
            : adjustment.compareTo(BigDecimal.ZERO) < 0
                ? "Travel in off-peak band → negative time adjustment (discount vs reference fare)."
                : "Travel in inter-peak band → no time adjustment.");
        return rule2;
    }

    public Map<String, Object> buildPaidRulesBreakdown(
        String ticketOrigin,
        String ticketDestination,
        String segmentOrigin,
        String segmentDestination,
        BigDecimal baseFareFromTable,
        Instant segmentStart
    ) {
        Map<String, Object> rules = new LinkedHashMap<>();
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("rule", "RULE_1_BASE_FARE");
        r1.put("summary", "Valid ticket exists; base reference fare is taken from the travelled segment on the route table.");
        r1.put("ticketOrigin", ticketOrigin);
        r1.put("ticketDestination", ticketDestination);
        r1.put("segmentOrigin", segmentOrigin);
        r1.put("segmentDestination", segmentDestination);
        r1.put("baseFare", scale(baseFareFromTable));
        rules.put("rule1_baseFare", r1);
        rules.put("rule2_timeValidity", timeBandBreakdown(baseFareFromTable, segmentStart));
        rules.put("rule3_overtravel", Map.of(
            "rule", "RULE_3_OVERTRAVEL",
            "overtravelAdjustment", BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            "summary", "No overtravel: entitlement covers the reconstructed segment."));
        rules.put("rule4_missingTicket", Map.of(
            "rule", "RULE_4_NO_TICKET_PENALTY",
            "applies", false,
            "summary", "Ticket coverage satisfied; penalty rule not invoked."));
        rules.put("rule5_confidenceGating", Map.of(
            "rule", "RULE_5_CONFIDENCE_GATING",
            "summary", "Automatic enforcement only when reconstruction confidence meets policy thresholds; PAID path has no penalty."));
        return rules;
    }

    public Map<String, Object> buildUnderpaidRulesBreakdown(
        String ticketOrigin,
        String ticketDestination,
        String segmentOrigin,
        String segmentDestination,
        BigDecimal actualJourneyFare,
        BigDecimal entitledJourneyFare,
        BigDecimal overtravelAdjustment,
        Instant segmentStart
    ) {
        Map<String, Object> rules = new LinkedHashMap<>();
        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("rule", "RULE_1_BASE_FARE");
        r1.put("summary", "Entitlement exists but is short for the reconstructed journey; base reference uses route table fares.");
        r1.put("ticketOrigin", ticketOrigin);
        r1.put("ticketDestination", ticketDestination);
        r1.put("entitledJourneyFare", scale(entitledJourneyFare));
        r1.put("actualJourneyFare", scale(actualJourneyFare));
        rules.put("rule1_baseFare", r1);
        rules.put("rule2_timeValidity", timeBandBreakdown(actualJourneyFare, segmentStart));
        Map<String, Object> r3 = new LinkedHashMap<>();
        r3.put("rule", "RULE_3_OVERTRAVEL");
        r3.put("formula", "max(0, fare(actual) - fare(entitled))");
        r3.put("actualJourneyFare", scale(actualJourneyFare));
        r3.put("entitledJourneyFare", scale(entitledJourneyFare));
        r3.put("overtravelAdjustment", scale(overtravelAdjustment));
        r3.put("summary", "Journey reconstruction extends beyond ticket destination; charge the incremental route fare.");
        rules.put("rule3_overtravel", r3);
        rules.put("rule4_missingTicket", Map.of(
            "rule", "RULE_4_NO_TICKET_PENALTY",
            "applies", false,
            "summary", "A ticket exists; apply additional/overtravel fare, not evasion penalty."));
        rules.put("rule5_confidenceGating", Map.of(
            "rule", "RULE_5_CONFIDENCE_GATING",
            "summary", "UNDERPAID may be held for review when confidence is borderline; see confidenceOutcome."));
        return rules;
    }

    public Map<String, Object> buildNoTicketRulesBreakdown(
        BigDecimal routeSingleFare,
        BigDecimal provisionalPenalty,
        Instant segmentStart
    ) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("rule1_baseFare", Map.of(
            "rule", "RULE_1_BASE_FARE",
            "applies", false,
            "summary", "No covering entitlement for this segment."));
        rules.put("rule2_timeValidity", timeBandBreakdown(routeSingleFare, segmentStart));
        rules.put("rule3_overtravel", Map.of(
            "rule", "RULE_3_OVERTRAVEL",
            "applies", false,
            "summary", "Not applicable without a valid ticket to compare entitled distance."));
        Map<String, Object> r4 = new LinkedHashMap<>();
        r4.put("rule", "RULE_4_NO_TICKET_PENALTY");
        r4.put("formula", "max(configuredPenalty, 2 * routeSingleFare)");
        r4.put("routeSingleFare", scale(routeSingleFare));
        r4.put("provisionalPenaltyIfUnpaid", scale(provisionalPenalty));
        r4.put("summary", "If enforcement proceeds after resolution window and confidence gates pass, apply the greater of flat penalty and twice the single fare.");
        rules.put("rule4_missingTicket", r4);
        rules.put("rule5_confidenceGating", Map.of(
            "rule", "RULE_5_CONFIDENCE_GATING",
            "summary", "Below auto-penalty confidence → PENDING_REVIEW, no passenger penalty notification; strong confidence → UNPAID_TRAVEL with penalty."));
        return rules;
    }

    public Map<String, Object> buildConfidenceEnforcementRule5(int autoPenaltyThreshold, int reviewThreshold, boolean enforced, Double confidenceScore) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rule", "RULE_5_CONFIDENCE_GATING");
        m.put("autoPenaltyThresholdPercent", autoPenaltyThreshold);
        m.put("reviewThresholdPercent", reviewThreshold);
        m.put("confidenceScore", confidenceScore);
        m.put("automaticEnforcementAllowed", enforced);
        m.put("summary", enforced
            ? "Confidence at or above auto-penalty threshold → automatic enforcement permitted."
            : "Confidence below auto-penalty threshold → case marked for review; penalty notification suppressed.");
        return m;
    }

    private static BigDecimal percentOfBase(BigDecimal base, int percentSigned) {
        if (base == null || base.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return base
            .multiply(BigDecimal.valueOf(percentSigned))
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal scale(BigDecimal v) {
        if (v == null) return null;
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}
