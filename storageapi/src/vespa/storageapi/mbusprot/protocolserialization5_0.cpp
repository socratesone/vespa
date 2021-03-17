// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "protocolserialization5_0.h"
#include "serializationhelper.h"
#include "storagecommand.h"
#include "storagereply.h"
#include <vespa/storageapi/message/bucketsplitting.h>
#include <vespa/storageapi/message/visitor.h>
#include <vespa/vdslib/state/clusterstate.h>
#include <vespa/document/update/documentupdate.h>
#include <vespa/document/bucket/fixed_bucket_spaces.h>
#include <sstream>

using document::BucketSpace;
using document::FixedBucketSpaces;

namespace storage::mbusprot {

document::Bucket
ProtocolSerialization5_0::getBucket(document::ByteBuffer& buf) const
{
    document::BucketId bucketId(SH::getLong(buf));
    return document::Bucket(FixedBucketSpaces::default_space(), bucketId);
}

void
ProtocolSerialization5_0::putBucket(const document::Bucket& bucket, vespalib::GrowableByteBuffer& buf) const
{
    buf.putLong(bucket.getBucketId().getRawId());
    if (bucket.getBucketSpace() != FixedBucketSpaces::default_space()) {
        std::ostringstream ost;
        ost << "Bucket with bucket space " << bucket.getBucketSpace() << " cannot be serialized on old storageapi protocol.";
        throw vespalib::IllegalArgumentException(ost.str(), VESPA_STRLOC);
    }
}

document::BucketSpace
ProtocolSerialization5_0::getBucketSpace(document::ByteBuffer&) const
{
    return FixedBucketSpaces::default_space();
}

void
ProtocolSerialization5_0::putBucketSpace(document::BucketSpace bucketSpace, vespalib::GrowableByteBuffer&) const
{
    if (bucketSpace != FixedBucketSpaces::default_space()) {
        std::ostringstream ost;
        ost << "Bucket space " << bucketSpace << " cannot be serialized on old storageapi protocol.";
        throw vespalib::IllegalArgumentException(ost.str(), VESPA_STRLOC);
    }
}

api::BucketInfo
ProtocolSerialization5_0::getBucketInfo(document::ByteBuffer& buf) const
{
    uint32_t crc(SH::getInt(buf));
    uint32_t doccount(SH::getInt(buf));
    uint32_t docsize(SH::getInt(buf));
    uint32_t metacount(SH::getInt(buf));
    uint32_t usedsize(SH::getInt(buf));
    return api::BucketInfo(crc, doccount, docsize, metacount, usedsize);
}

void
ProtocolSerialization5_0::putBucketInfo(const api::BucketInfo& info, vespalib::GrowableByteBuffer& buf) const
{
    buf.putInt(info.getChecksum());
    buf.putInt(info.getDocumentCount());
    buf.putInt(info.getTotalDocumentSize());
    buf.putInt(info.getMetaCount());
    buf.putInt(info.getUsedFileSize());
}

void
ProtocolSerialization5_0::onEncodeReply(GBBuf& buf, const api::StorageReply& msg) const
{
    SH::putReturnCode(msg.getResult(), buf);
    buf.putLong(msg.getMsgId());
    buf.putByte(msg.getPriority());
}

void
ProtocolSerialization5_0::onDecodeReply(BBuf& buf, api::StorageReply& msg) const
{
    msg.setResult(SH::getReturnCode(buf));
    msg.forceMsgId(SH::getLong(buf));
    msg.setPriority(SH::getByte(buf));
}

void
ProtocolSerialization5_0::onEncodeCommand(GBBuf& buf, const api::StorageCommand& msg) const
{
    buf.putLong(msg.getMsgId());
    buf.putByte(msg.getPriority());
    buf.putShort(msg.getSourceIndex());
    buf.putInt(0); // LoadType 'default'
}

void
ProtocolSerialization5_0::onDecodeCommand(BBuf& buf, api::StorageCommand& msg) const
{
    msg.forceMsgId(SH::getLong(buf));
    uint8_t priority = SH::getByte(buf);
    msg.setPriority(priority);
    msg.setSourceIndex(SH::getShort(buf));
    (void)SH::getInt(buf); // LoadType
}


ProtocolSerialization5_0::ProtocolSerialization5_0(const std::shared_ptr<const document::DocumentTypeRepo>& repo)
    : ProtocolSerialization4_2(repo)
{
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::PutReply& msg) const
{
    buf.putBoolean(msg.wasFound());
    onEncodeBucketInfoReply(buf, msg);
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::PutCommand& msg) const
{
    SH::putDocument(msg.getDocument().get(), buf);
    putBucket(msg.getBucket(), buf);
    buf.putLong(msg.getTimestamp());
    buf.putLong(msg.getUpdateTimestamp());
    onEncodeBucketInfoCommand(buf, msg);
}

api::StorageCommand::UP
ProtocolSerialization5_0::onDecodePutCommand(BBuf& buf) const
{
    document::Document::SP doc(SH::getDocument(buf, getTypeRepo()));
    document::Bucket bucket = getBucket(buf);
    api::Timestamp ts(SH::getLong(buf));
    auto msg = std::make_unique<api::PutCommand>(bucket, doc, ts);
    msg->setUpdateTimestamp(SH::getLong(buf));
    onDecodeBucketInfoCommand(buf, *msg);
    return msg;
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodePutReply(const SCmd& cmd, BBuf& buf) const
{
    bool wasFound = SH::getBoolean(buf);
    auto msg = std::make_unique<api::PutReply>(static_cast<const api::PutCommand&>(cmd), wasFound);
    onDecodeBucketInfoReply(buf, *msg);
    return msg;
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::UpdateReply& msg) const
{
    buf.putLong(msg.getOldTimestamp());
    onEncodeBucketInfoReply(buf, msg);
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeUpdateReply(const SCmd& cmd, BBuf& buf) const
{
    api::Timestamp oldTimestamp(SH::getLong(buf));
    auto msg = std::make_unique<api::UpdateReply>(static_cast<const api::UpdateCommand&>(cmd), oldTimestamp);
    onDecodeBucketInfoReply(buf, *msg);
    return msg;
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::GetReply& msg) const
{
    SH::putDocument(msg.getDocument().get(), buf);
    // Old protocol version doesn't understand tombstones. Make it appear as Not Found.
    buf.putLong(msg.is_tombstone() ? api::Timestamp(0) : msg.getLastModifiedTimestamp());
    onEncodeBucketInfoReply(buf, msg);
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeGetReply(const SCmd& cmd, BBuf& buf) const
{
    try {
        document::Document::SP doc(SH::getDocument(buf, getTypeRepo()));
        api::Timestamp lastModified(SH::getLong(buf));
        auto msg = std::make_unique<api::GetReply>(static_cast<const api::GetCommand&>(cmd), doc,lastModified);
        onDecodeBucketInfoReply(buf, *msg);
        return msg;
    } catch (std::exception& e) {
        auto msg = std::make_unique<api::GetReply>(static_cast<const api::GetCommand&>(cmd), document::Document::SP(),0);
        msg->setResult(api::ReturnCode(api::ReturnCode::UNPARSEABLE, e.what()));
        return msg;
    }
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::RemoveReply& msg) const
{
    buf.putLong(msg.getOldTimestamp());
    onEncodeBucketInfoReply(buf, msg);
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeRemoveReply(const SCmd& cmd, BBuf& buf) const
{
    api::Timestamp oldTimestamp(SH::getLong(buf));
    auto msg = std::make_unique<api::RemoveReply>(static_cast<const api::RemoveCommand&>(cmd), oldTimestamp);
    onDecodeBucketInfoReply(buf, *msg);
    return msg;
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::UpdateCommand& msg) const
{
    document::DocumentUpdate* update = msg.getUpdate().get();
    if (update) {
        vespalib::nbostream stream;
        update->serializeHEAD(stream);
        buf.putInt(stream.size());
        buf.putBytes(stream.peek(), stream.size());
    } else {
        buf.putInt(0);
    }

    putBucket(msg.getBucket(), buf);
    buf.putLong(msg.getTimestamp());
    buf.putLong(msg.getOldTimestamp());
    onEncodeBucketInfoCommand(buf, msg);
}

api::StorageCommand::UP
ProtocolSerialization5_0::onDecodeUpdateCommand(BBuf& buf) const
{
    document::DocumentUpdate::SP update;

    uint32_t size = SH::getInt(buf);
    if (size != 0) {
        update = document::DocumentUpdate::createHEAD(getTypeRepo(), vespalib::nbostream(buf.getBufferAtPos(), size));
        buf.incPos(size);
    }

    document::Bucket bucket = getBucket(buf);
    api::Timestamp timestamp(SH::getLong(buf));
    api::UpdateCommand::UP msg = std::make_unique<api::UpdateCommand>(bucket, update, timestamp);
    msg->setOldTimestamp(SH::getLong(buf));
    onDecodeBucketInfoCommand(buf, *msg);
    return msg;
}


void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::RevertReply& msg) const
{
    onEncodeBucketInfoReply(buf, msg);
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeRevertReply(const SCmd& cmd, BBuf& buf) const
{
    auto msg = std::make_unique<api::RevertReply>(static_cast<const api::RevertCommand&>(cmd));
    onDecodeBucketInfoReply(buf, *msg);
    return msg;
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::CreateBucketReply& msg) const
{
    onEncodeBucketInfoReply(buf, msg);
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeCreateBucketReply(const SCmd& cmd, BBuf& buf) const
{
    auto msg = std::make_unique<api::CreateBucketReply>(static_cast<const api::CreateBucketCommand&>(cmd));
    onDecodeBucketInfoReply(buf, *msg);
    return msg;
}

void
ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::DeleteBucketCommand& msg) const
{
    putBucket(msg.getBucket(), buf);
    onEncodeBucketInfoCommand(buf, msg);
    putBucketInfo(msg.getBucketInfo(), buf);
}

api::StorageCommand::UP
ProtocolSerialization5_0::onDecodeDeleteBucketCommand(BBuf& buf) const
{
    document::Bucket bucket = getBucket(buf);
    auto msg = std::make_unique<api::DeleteBucketCommand>(bucket);
    onDecodeBucketInfoCommand(buf, *msg);
    if (buf.getRemaining() >= SH::BUCKET_INFO_SERIALIZED_SIZE) {
        msg->setBucketInfo(getBucketInfo(buf));
    }
    return msg;
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::DeleteBucketReply& msg) const
{
    onEncodeBucketInfoReply(buf, msg);
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeDeleteBucketReply(const SCmd& cmd, BBuf& buf) const
{
    auto msg = std::make_unique<api::DeleteBucketReply>(static_cast<const api::DeleteBucketCommand&>(cmd));
    onDecodeBucketInfoReply(buf, *msg);
    return msg;
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::MergeBucketCommand& msg) const
{
    ProtocolSerialization4_2::onEncode(buf, msg);

    buf.putInt(msg.getClusterStateVersion());
    const std::vector<uint16_t>& chain(msg.getChain());
    buf.putShort(chain.size());
    for (std::size_t i = 0; i < chain.size(); ++i) {
        buf.putShort(chain[i]);
    }
}

api::StorageCommand::UP
ProtocolSerialization5_0::onDecodeMergeBucketCommand(BBuf& buf) const
{
    api::StorageCommand::UP cmd = ProtocolSerialization4_2::onDecodeMergeBucketCommand(buf);
    uint32_t clusterStateVersion = SH::getInt(buf);
    uint16_t chainSize = SH::getShort(buf);
    std::vector<uint16_t> chain;
    chain.reserve(chainSize);
    for (std::size_t i = 0; i < chainSize; ++i) {
        uint16_t index = SH::getShort(buf);
        chain.push_back(index);
    }
    api::MergeBucketCommand& mergeCmd = static_cast<api::MergeBucketCommand&>(*cmd);
    mergeCmd.setChain(chain);
    mergeCmd.setClusterStateVersion(clusterStateVersion);
    return cmd;
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::MergeBucketReply& msg) const
{
    onEncodeBucketReply(buf, msg);
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeMergeBucketReply(const SCmd& cmd, BBuf& buf) const
{
    auto msg = std::make_unique<api::MergeBucketReply>(static_cast<const api::MergeBucketCommand&>(cmd));
    onDecodeBucketReply(buf, *msg);
    return msg;
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::GetBucketDiffReply& msg) const
{
    const std::vector<api::GetBucketDiffCommand::Entry>& entries(msg.getDiff());
    buf.putInt(entries.size());
    for (uint32_t i=0; i<entries.size(); ++i) {
        onEncodeDiffEntry(buf, entries[i]);
    }
    onEncodeBucketReply(buf, msg);
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeGetBucketDiffReply(const SCmd& cmd, BBuf& buf) const
{
    auto msg = std::make_unique<api::GetBucketDiffReply>(static_cast<const api::GetBucketDiffCommand&>(cmd));
    std::vector<api::GetBucketDiffCommand::Entry>& entries(msg->getDiff());
    uint32_t entryCount = SH::getInt(buf);
    if (entryCount > buf.getRemaining()) {
            // Trigger out of bounds exception rather than out of memory error
        buf.incPos(entryCount);
    }
    entries.resize(entryCount);
    for (uint32_t i=0; i<entries.size(); ++i) {
        onDecodeDiffEntry(buf, entries[i]);
    }
    onDecodeBucketReply(buf, *msg);
    return msg;
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::ApplyBucketDiffReply& msg) const
{
    const std::vector<api::ApplyBucketDiffCommand::Entry>& entries(msg.getDiff());
    buf.putInt(entries.size());
    for (uint32_t i=0; i<entries.size(); ++i) {
        onEncodeDiffEntry(buf, entries[i]._entry);
        buf.putString(entries[i]._docName);
        buf.putInt(entries[i]._headerBlob.size());
        buf.putBytes(&entries[i]._headerBlob[0], entries[i]._headerBlob.size());
        buf.putInt(entries[i]._bodyBlob.size());
        buf.putBytes(&entries[i]._bodyBlob[0], entries[i]._bodyBlob.size());
    }
    onEncodeBucketInfoReply(buf, msg);
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeApplyBucketDiffReply(const SCmd& cmd,
                                                       BBuf& buf) const
{
    auto msg = std::make_unique<api::ApplyBucketDiffReply>(static_cast<const api::ApplyBucketDiffCommand&>(cmd));
    std::vector<api::ApplyBucketDiffCommand::Entry>& entries(msg->getDiff());
    uint32_t entryCount = SH::getInt(buf);
    if (entryCount > buf.getRemaining()) {
            // Trigger out of bounds exception rather than out of memory error
        buf.incPos(entryCount);
    }
    entries.resize(entryCount);
    for (uint32_t i=0; i<entries.size(); ++i) {
        onDecodeDiffEntry(buf, entries[i]._entry);
        entries[i]._docName = SH::getString(buf);
        uint32_t headerSize = SH::getInt(buf);
        if (headerSize > buf.getRemaining()) {
            buf.incPos(headerSize);
        }
        entries[i]._headerBlob.resize(headerSize);
        buf.getBytes(&entries[i]._headerBlob[0], entries[i]._headerBlob.size());
        uint32_t bodySize = SH::getInt(buf);
        if (bodySize > buf.getRemaining()) {
            buf.incPos(bodySize);
        }
        entries[i]._bodyBlob.resize(bodySize);
        buf.getBytes(&entries[i]._bodyBlob[0], entries[i]._bodyBlob.size());
    }
    onDecodeBucketInfoReply(buf, *msg);
    return msg;
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::SplitBucketReply& msg) const
{
    const std::vector<api::SplitBucketReply::Entry>& entries(msg.getSplitInfo());
    buf.putInt(entries.size());
    for (std::vector<api::SplitBucketReply::Entry>::const_iterator it
            = entries.begin(); it != entries.end(); ++it)
    {
        buf.putLong(it->first.getRawId());
        putBucketInfo(it->second, buf);
    }
    onEncodeBucketReply(buf, msg);
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeSplitBucketReply(const SCmd& cmd, BBuf& buf) const
{
    auto msg = std::make_unique<api::SplitBucketReply>(static_cast<const api::SplitBucketCommand&>(cmd));
    std::vector<api::SplitBucketReply::Entry>& entries(msg->getSplitInfo());
    uint32_t targetCount = SH::getInt(buf);
    if (targetCount > buf.getRemaining()) {
            // Trigger out of bounds exception rather than out of memory error
        buf.incPos(targetCount);
    }
    entries.resize(targetCount);
    for (std::vector<api::SplitBucketReply::Entry>::iterator it
            = entries.begin(); it != entries.end(); ++it)
    {
        it->first = document::BucketId(SH::getLong(buf));
        it->second = getBucketInfo(buf);
    }
    onDecodeBucketReply(buf, *msg);
    return msg;
}

void
ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::JoinBucketsCommand& msg) const
{
    putBucket(msg.getBucket(), buf);
    buf.putInt(msg.getSourceBuckets().size());
    for (uint32_t i=0, n=msg.getSourceBuckets().size(); i<n; ++i) {
        buf.putLong(msg.getSourceBuckets()[i].getRawId());
    }
    buf.putByte(msg.getMinJoinBits());
    onEncodeCommand(buf, msg);
}

api::StorageCommand::UP
ProtocolSerialization5_0::onDecodeJoinBucketsCommand(BBuf& buf) const
{
    document::Bucket bucket = getBucket(buf);
    auto msg = std::make_unique<api::JoinBucketsCommand>(bucket);
    uint32_t size = SH::getInt(buf);
    if (size > buf.getRemaining()) {
        // Trigger out of bounds exception rather than out of memory error
        buf.incPos(size);
    }
    std::vector<document::BucketId>& entries(msg->getSourceBuckets());
    for (uint32_t i=0; i<size; ++i) {
        entries.push_back(document::BucketId(SH::getLong(buf)));
    }
    msg->setMinJoinBits(SH::getByte(buf));
    onDecodeCommand(buf, *msg);
    return msg;
}

void
ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::JoinBucketsReply& msg) const
{
    putBucketInfo(msg.getBucketInfo(), buf);
    onEncodeBucketReply(buf, msg);
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeJoinBucketsReply(const SCmd& cmd, BBuf& buf) const
{
    auto msg = std::make_unique<api::JoinBucketsReply>(static_cast<const api::JoinBucketsCommand&>(cmd));
    msg->setBucketInfo(getBucketInfo(buf));
    onDecodeBucketReply(buf, *msg);
    return msg;
}

void
ProtocolSerialization5_0::onEncodeBucketInfoReply(GBBuf& buf, const api::BucketInfoReply& msg) const
{
    onEncodeBucketReply(buf, msg);
    putBucketInfo(msg.getBucketInfo(), buf);
}

void
ProtocolSerialization5_0::onDecodeBucketInfoReply(BBuf& buf, api::BucketInfoReply& msg) const
{
    onDecodeBucketReply(buf, msg);
    msg.setBucketInfo(getBucketInfo(buf));
}

void
ProtocolSerialization5_0::onEncodeBucketReply(GBBuf& buf, const api::BucketReply& msg) const
{
    onEncodeReply(buf, msg);
    buf.putLong(msg.hasBeenRemapped() ? msg.getBucketId().getRawId() : 0);
}

void
ProtocolSerialization5_0::onDecodeBucketReply(BBuf& buf, api::BucketReply& msg) const
{
    onDecodeReply(buf, msg);
    document::BucketId bucket(SH::getLong(buf));
    if (bucket.getRawId() != 0) {
        msg.remapBucketId(bucket);
    }
}

void
ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::CreateVisitorReply& msg) const
{
    onEncodeReply(buf, msg);
    buf.putInt(msg.getVisitorStatistics().getBucketsVisited());
    buf.putLong(msg.getVisitorStatistics().getDocumentsVisited());
    buf.putLong(msg.getVisitorStatistics().getBytesVisited());
    buf.putLong(msg.getVisitorStatistics().getDocumentsReturned());
    buf.putLong(msg.getVisitorStatistics().getBytesReturned());
    buf.putLong(msg.getVisitorStatistics().getSecondPassDocumentsReturned());
    buf.putLong(msg.getVisitorStatistics().getSecondPassBytesReturned());
}

api::StorageReply::UP
ProtocolSerialization5_0::onDecodeCreateVisitorReply(const SCmd& cmd, BBuf& buf) const
{
    auto msg = std::make_unique<api::CreateVisitorReply>(static_cast<const api::CreateVisitorCommand&>(cmd));
    onDecodeReply(buf, *msg);

    vdslib::VisitorStatistics vs;
    vs.setBucketsVisited(SH::getInt(buf));
    vs.setDocumentsVisited(SH::getLong(buf));
    vs.setBytesVisited(SH::getLong(buf));
    vs.setDocumentsReturned(SH::getLong(buf));
    vs.setBytesReturned(SH::getLong(buf));
    vs.setSecondPassDocumentsReturned(SH::getLong(buf));
    vs.setSecondPassBytesReturned(SH::getLong(buf));
    msg->setVisitorStatistics(vs);

    return msg;
}

void ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::RequestBucketInfoCommand& msg) const
{
    const std::vector<document::BucketId>& buckets(msg.getBuckets());
    buf.putInt(buckets.size());
    for (uint32_t i=0; i<buckets.size(); ++i) {
        buf.putLong(buckets[i].getRawId());
    }
    putBucketSpace(msg.getBucketSpace(), buf);
    if (buckets.size() == 0) {
        buf.putShort(msg.getDistributor());
        buf.putString(msg.getSystemState().toString());
        buf.putString(msg.getDistributionHash());
    }

    onEncodeCommand(buf, msg);
}

api::StorageCommand::UP
ProtocolSerialization5_0::onDecodeRequestBucketInfoCommand(BBuf& buf) const
{
    std::vector<document::BucketId> buckets(SH::getInt(buf));
    for (uint32_t i=0; i<buckets.size(); ++i) {
        buckets[i] = document::BucketId(SH::getLong(buf));
    }
    api::RequestBucketInfoCommand::UP msg;
    BucketSpace bucketSpace = getBucketSpace(buf);
    if (buckets.size() != 0) {
        msg.reset(new api::RequestBucketInfoCommand(bucketSpace, buckets));
    } else {
        int distributor = SH::getShort(buf);
        lib::ClusterState state(SH::getString(buf));
        msg.reset(new api::RequestBucketInfoCommand(bucketSpace, distributor, state, SH::getString(buf)));
    }
    onDecodeCommand(buf, *msg);
    return msg;
}

void
ProtocolSerialization5_0::onEncode(GBBuf& buf, const api::CreateVisitorCommand& cmd) const
{
    ProtocolSerialization4_2::onEncode(buf, cmd);

    buf.putInt(0);  // Unused
    buf.putInt(cmd.getMaxBucketsPerVisitor());
}

api::StorageCommand::UP
ProtocolSerialization5_0::onDecodeCreateVisitorCommand(BBuf& buf) const
{
    api::StorageCommand::UP cvc = ProtocolSerialization4_2::onDecodeCreateVisitorCommand(buf);
    SH::getInt(buf); // Unused

    static_cast<api::CreateVisitorCommand*>(cvc.get())->setMaxBucketsPerVisitor(SH::getInt(buf));

    static_cast<api::CreateVisitorCommand*>(cvc.get())->setVisitorDispatcherVersion(50);
    return cvc;
}

}
