/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.sync.importing.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.exception.ThingsboardException;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.plugin.ComponentLifecycleEvent;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainMetaData;
import org.thingsboard.server.common.data.rule.RuleChainType;
import org.thingsboard.server.dao.rule.RuleChainService;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.security.model.SecurityUser;
import org.thingsboard.server.service.sync.exporting.data.RuleChainExportData;

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

@Service
@TbCoreComponent
@RequiredArgsConstructor
public class RuleChainImportService extends BaseEntityImportService<RuleChainId, RuleChain, RuleChainExportData> {

    private final RuleChainService ruleChainService;

    @Override
    protected void setOwner(TenantId tenantId, RuleChain ruleChain, NewIdProvider idProvider) {
        ruleChain.setTenantId(tenantId);
    }

    @Override
    protected RuleChain prepareAndSave(TenantId tenantId, RuleChain ruleChain, RuleChainExportData exportData, NewIdProvider idProvider) {
        RuleChainMetaData metaData = exportData.getMetaData();
        Optional.ofNullable(metaData.getNodes()).orElse(Collections.emptyList())
                .forEach(ruleNode -> {
                    ruleNode.setId(null);
                    ruleNode.setRuleChainId(null);
                    JsonNode ruleNodeConfig = ruleNode.getConfiguration();
                    Optional.ofNullable(ruleNodeConfig)
                            .flatMap(config -> Optional.ofNullable(config.get("ruleChainId")).filter(JsonNode::isTextual))
                            .map(JsonNode::asText).map(UUID::fromString)
                            .ifPresent(otherRuleChainUuid -> {
                                ((ObjectNode) ruleNodeConfig).set("ruleChainId", new TextNode(
                                        idProvider.getInternal(new RuleChainId(otherRuleChainUuid)).toString()
                                ));
                                ruleNode.setConfiguration(ruleNodeConfig);
                            });
                });
        Optional.ofNullable(metaData.getRuleChainConnections()).orElse(Collections.emptyList())
                .forEach(ruleChainConnectionInfo -> {
                    ruleChainConnectionInfo.setTargetRuleChainId(idProvider.getInternal(ruleChainConnectionInfo.getTargetRuleChainId()));
                });
        ruleChain.setFirstRuleNodeId(null);

        if (ruleChain.getId() != null) {
            // FIXME [viacheslav]: maybe no need to delete
            ruleChainService.deleteRuleNodes(tenantId, ruleChain.getId());
        }
        ruleChain = ruleChainService.saveRuleChain(ruleChain);
        exportData.getMetaData().setRuleChainId(ruleChain.getId());
        ruleChainService.saveRuleChainMetaData(tenantId, exportData.getMetaData());
        return ruleChainService.findRuleChainById(tenantId, ruleChain.getId());
    }

    @Override
    protected void onEntitySaved(SecurityUser user, RuleChain savedRuleChain, RuleChain oldRuleChain) throws ThingsboardException {
        super.onEntitySaved(user, savedRuleChain, oldRuleChain);
        if (savedRuleChain.getType() == RuleChainType.CORE) {
            clusterService.broadcastEntityStateChangeEvent(user.getTenantId(), savedRuleChain.getId(),
                    oldRuleChain == null ? ComponentLifecycleEvent.CREATED : ComponentLifecycleEvent.UPDATED);
        } else if (savedRuleChain.getType() == RuleChainType.EDGE && oldRuleChain != null) {
            entityActionService.sendEntityNotificationMsgToEdgeService(user.getTenantId(), savedRuleChain.getId(), EdgeEventActionType.UPDATED);
        }
    }

    @Override
    public EntityType getEntityType() {
        return EntityType.RULE_CHAIN;
    }

}
