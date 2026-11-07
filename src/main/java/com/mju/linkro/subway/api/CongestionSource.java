package com.mju.linkro.subway.api;

/**
 * Where a congestion value comes from. When one slot has several, the lookup returns one in the order
 * PREDICTED > ESTIMATED > MOCK (declaration order).
 *
 * <ul>
 *   <li>PREDICTED: the team's AI model prediction (model_version stored)</li>
 *   <li>ESTIMATED: derived by the loader where the AI file has no row (2호선 성수 OUTER, mean of its neighbours)</li>
 *   <li>MOCK: placeholder data</li>
 * </ul>
 */
public enum CongestionSource { PREDICTED, ESTIMATED, MOCK }
