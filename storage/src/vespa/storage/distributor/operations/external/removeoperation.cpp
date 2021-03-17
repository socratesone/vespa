// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "removeoperation.h"
#include <vespa/storageapi/message/persistence.h>
#include <vespa/storage/distributor/distributor_bucket_space.h>
#include <vespa/vdslib/state/clusterstate.h>

#include <vespa/log/log.h>
LOG_SETUP(".distributor.operation.external.remove");


using namespace storage::distributor;
using namespace storage;
using document::BucketSpace;

RemoveOperation::RemoveOperation(DistributorNodeContext& node_ctx,
                                 DistributorOperationContext& op_ctx,
                                 DistributorBucketSpace &bucketSpace,
                                 std::shared_ptr<api::RemoveCommand> msg,
                                 PersistenceOperationMetricSet& metric,
                                 SequencingHandle sequencingHandle)
    : SequencedOperation(std::move(sequencingHandle)),
      _trackerInstance(metric,
               std::make_shared<api::RemoveReply>(*msg),
               node_ctx, op_ctx, msg->getTimestamp()),
      _tracker(_trackerInstance),
      _msg(std::move(msg)),
      _node_ctx(node_ctx),
      _bucketSpace(bucketSpace)
{
}

RemoveOperation::~RemoveOperation() = default;

void
RemoveOperation::onStart(DistributorMessageSender& sender)
{
    LOG(spam, "Started remove on document %s", _msg->getDocumentId().toString().c_str());

    document::BucketId bucketId(
            _node_ctx.bucket_id_factory().getBucketId(
                    _msg->getDocumentId()));

    std::vector<BucketDatabase::Entry> entries;
    _bucketSpace.getBucketDatabase().getParents(bucketId, entries);

    bool sent = false;

    for (const BucketDatabase::Entry& e : entries) {
        std::vector<MessageTracker::ToSend> messages;
        messages.reserve(e->getNodeCount());
        for (uint32_t i = 0; i < e->getNodeCount(); i++) {
            auto command = std::make_shared<api::RemoveCommand>(document::Bucket(_msg->getBucket().getBucketSpace(), e.getBucketId()),
                                                                _msg->getDocumentId(),
                                                                _msg->getTimestamp());

            copyMessageSettings(*_msg, *command);
            command->getTrace().setLevel(_msg->getTrace().getLevel());
            command->setCondition(_msg->getCondition());

            messages.emplace_back(std::move(command), e->getNodeRef(i).getNode());
            sent = true;
        }

        _tracker.queueMessageBatch(messages);
    }

    if (!sent) {
        LOG(debug,
            "Remove document %s failed since no available nodes found. "
            "System state is %s",
            _msg->getDocumentId().toString().c_str(),
            _bucketSpace.getClusterState().toString().c_str());

        _tracker.fail(sender, api::ReturnCode(api::ReturnCode::OK));
    } else {
        _tracker.flushQueue(sender);
    }
};


void
RemoveOperation::onReceive(DistributorMessageSender& sender, const std::shared_ptr<api::StorageReply> & msg)
{
    api::RemoveReply& reply(static_cast<api::RemoveReply&>(*msg));

    if (_tracker.getReply().get()) {
        api::RemoveReply& replyToSend =
            static_cast<api::RemoveReply&>(*_tracker.getReply());

        if (reply.getOldTimestamp() > replyToSend.getOldTimestamp()) {
            replyToSend.setOldTimestamp(reply.getOldTimestamp());
        }
    }

    _tracker.receiveReply(sender, reply);
}

void
RemoveOperation::onClose(DistributorMessageSender& sender)
{
    _tracker.fail(sender, api::ReturnCode(api::ReturnCode::ABORTED, "Process is shutting down"));
}
