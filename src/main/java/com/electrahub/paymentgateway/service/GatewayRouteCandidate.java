package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentRoute;

record GatewayRouteCandidate(PaymentRoute route, GatewayConnection connection) {
}
