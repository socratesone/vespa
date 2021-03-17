// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "singlesmallnumericattribute.h"
#include "attributevector.hpp"
#include "primitivereader.h"
#include "attributeiterators.hpp"
#include "iattributesavetarget.h"
#include <vespa/searchlib/query/query_term_simple.h>
#include <vespa/searchlib/queryeval/emptysearch.h>
#include <vespa/vespalib/data/databuffer.h>
#include <vespa/vespalib/util/size_literals.h>

namespace search {

SingleValueSmallNumericAttribute::
SingleValueSmallNumericAttribute(const vespalib::string & baseFileName,
                                 const Config & c,
                                 Word valueMask,
                                 uint32_t valueShiftShift,
                                 uint32_t valueShiftMask,
                                 uint32_t wordShift)
    : B(baseFileName, c, c.basicType()),
      _valueMask(valueMask),
      _valueShiftShift(valueShiftShift),
      _valueShiftMask(valueShiftMask),
      _wordShift(wordShift),
      _wordData(c.getGrowStrategy().getDocsInitialCapacity(),
                c.getGrowStrategy().getDocsGrowPercent(),
                c.getGrowStrategy().getDocsGrowDelta(),
                getGenerationHolder())
{
    assert(_valueMask + 1 == (1u << (1u << valueShiftShift)));
    assert((_valueShiftMask + 1) * (1u << valueShiftShift) == 8 * sizeof(Word));
    assert(_valueShiftMask + 1 == (1u << wordShift));
}


SingleValueSmallNumericAttribute::~SingleValueSmallNumericAttribute()
{
    getGenerationHolder().clearHoldLists();
}

void
SingleValueSmallNumericAttribute::onAddDocs(DocId lidLimit) {
    _wordData.reserve((lidLimit >> _wordShift) + 1);
}

void
SingleValueSmallNumericAttribute::onCommit()
{
    checkSetMaxValueCount(1);

    {
        // apply updates
        B::ValueModifier valueGuard(getValueModifier());
        for (const auto & change : _changes) {
            if (change._type == ChangeBase::UPDATE) {
                std::atomic_thread_fence(std::memory_order_release);
                set(change._doc, change._data);
            } else if (change._type >= ChangeBase::ADD && change._type <= ChangeBase::DIV) {
                std::atomic_thread_fence(std::memory_order_release);
                set(change._doc, applyArithmetic(getFast(change._doc), change));
            } else if (change._type == ChangeBase::CLEARDOC) {
                std::atomic_thread_fence(std::memory_order_release);
                set(change._doc, 0u);
            }
        }
    }

    std::atomic_thread_fence(std::memory_order_release);
    removeAllOldGenerations();

    _changes.clear();
}

bool
SingleValueSmallNumericAttribute::addDoc(DocId & doc) {
    if ((B::getNumDocs() & _valueShiftMask) == 0) {
        bool incGen = _wordData.isFull();
        _wordData.push_back(Word());
        std::atomic_thread_fence(std::memory_order_release);
        B::incNumDocs();
        doc = B::getNumDocs() - 1;
        updateUncommittedDocIdLimit(doc);
        if (incGen) {
            this->incGeneration();
        } else
            this->removeAllOldGenerations();
    } else {
        B::incNumDocs();
        doc = B::getNumDocs() - 1;
        updateUncommittedDocIdLimit(doc);
    }
    return true;
}

void
SingleValueSmallNumericAttribute::onUpdateStat()
{
    vespalib::MemoryUsage usage = _wordData.getMemoryUsage();
    usage.mergeGenerationHeldBytes(getGenerationHolder().getHeldBytes());
    uint32_t numDocs = B::getNumDocs();
    updateStatistics(numDocs, numDocs,
                     usage.allocatedBytes(), usage.usedBytes(),
                     usage.deadBytes(), usage.allocatedBytesOnHold());
}


void
SingleValueSmallNumericAttribute::removeOldGenerations(generation_t firstUsed)
{
    getGenerationHolder().trimHoldLists(firstUsed);
}


void
SingleValueSmallNumericAttribute::onGenerationChange(generation_t generation)
{
    getGenerationHolder().transferHoldLists(generation - 1);
}


bool
SingleValueSmallNumericAttribute::onLoad()
{
    PrimitiveReader<Word> attrReader(*this);
    bool ok(attrReader.hasData());
    if (ok) {
        setCreateSerialNum(attrReader.getCreateSerialNum());
        const size_t sz(attrReader.getDataCount());
        getGenerationHolder().clearHoldLists();
        _wordData.reset();
        _wordData.unsafe_reserve(sz - 1);
        Word numDocs = attrReader.getNextData();
        for (uint32_t i = 1; i < sz; ++i) {
            _wordData.push_back(attrReader.getNextData());
        }
        assert(((numDocs + _valueShiftMask) >> _wordShift) + 1 == sz);
        B::setNumDocs(numDocs);
        B::setCommittedDocIdLimit(numDocs);
    }

    return ok;
}


void
SingleValueSmallNumericAttribute::onSave(IAttributeSaveTarget &saveTarget)
{
    assert(!saveTarget.getEnumerated());
    const size_t numDocs(getCommittedDocIdLimit());
    const size_t numDataWords((numDocs + _valueShiftMask) >> _wordShift);
    const size_t sz((numDataWords + 1) * sizeof(Word));
    IAttributeSaveTarget::Buffer buf(saveTarget.datWriter().allocBuf(sz));

    char *p = buf->getFree();
    const char *e = p + sz;
    Word numDocs2 = numDocs;
    memcpy(p, &numDocs2, sizeof(Word));
    p += sizeof(Word);
    memcpy(p, &_wordData[0], numDataWords * sizeof(Word));
    p += numDataWords * sizeof(Word);
    assert(p == e);
    (void) e;
    buf->moveFreeToData(sz);
    saveTarget.datWriter().writeBuf(std::move(buf));
    assert(numDocs == getCommittedDocIdLimit());
}

AttributeVector::SearchContext::UP
SingleValueSmallNumericAttribute::getSearch(std::unique_ptr<QueryTermSimple> qTerm,
                                            const attribute::SearchContextParams &) const
{
    return std::make_unique<SingleSearchContext>(std::move(qTerm), *this);
}

void
SingleValueSmallNumericAttribute::clearDocs(DocId lidLow, DocId lidLimit)
{
    assert(lidLow <= lidLimit);
    assert(lidLimit <= getNumDocs());
    for (DocId lid = lidLow; lid < lidLimit; ++lid) {
        if (getFast(lid) != 0) {
            clearDoc(lid);
        }
    }
}

void
SingleValueSmallNumericAttribute::onShrinkLidSpace()
{
    uint32_t committedDocIdLimit = getCommittedDocIdLimit();
    assert(committedDocIdLimit < getNumDocs());
    const size_t numDocs(committedDocIdLimit);
    const size_t numDataWords((numDocs + _valueShiftMask) >> _wordShift);
    _wordData.shrink(numDataWords);
    setNumDocs(committedDocIdLimit);
}

uint64_t
SingleValueSmallNumericAttribute::getEstimatedSaveByteSize() const
{
    uint64_t headerSize = 4_Ki;
    const size_t numDocs(getCommittedDocIdLimit());
    const size_t numDataWords((numDocs + _valueShiftMask) >> _wordShift);
    const size_t sz((numDataWords + 1) * sizeof(Word));
    return headerSize + sz;
}

bool SingleValueSmallNumericAttribute::SingleSearchContext::valid() const { return this->isValid(); }


SingleValueSmallNumericAttribute::SingleSearchContext::SingleSearchContext(std::unique_ptr<QueryTermSimple> qTerm,
                                                                           const SingleValueSmallNumericAttribute & toBeSearched)
    : NumericAttribute::Range<T>(*qTerm),
      SearchContext(toBeSearched), _wordData(&toBeSearched._wordData[0]),
      _valueMask(toBeSearched._valueMask),
      _valueShiftShift(toBeSearched._valueShiftShift),
      _valueShiftMask(toBeSearched._valueShiftMask),
      _wordShift(toBeSearched._wordShift)
{ }

Int64Range
SingleValueSmallNumericAttribute::SingleSearchContext::getAsIntegerTerm() const {
    return this->getRange();
}

std::unique_ptr<queryeval::SearchIterator>
SingleValueSmallNumericAttribute::SingleSearchContext::createFilterIterator(fef::TermFieldMatchData * matchData, bool strict)
{
    if (!valid()) {
        return std::make_unique<queryeval::EmptySearch>();
    }
    if (getIsFilter()) {
        return strict
                 ? std::make_unique<FilterAttributeIteratorStrict<SingleSearchContext>>(*this, matchData)
                 : std::make_unique<FilterAttributeIteratorT<SingleSearchContext>>(*this, matchData);
    }
    return strict
             ? std::make_unique<AttributeIteratorStrict<SingleSearchContext>>(*this, matchData)
             : std::make_unique<AttributeIteratorT<SingleSearchContext>>(*this, matchData);
}

namespace {

template <typename TT>
uint32_t
log2bits();

template <>
uint32_t
log2bits<uint32_t>()
{
    return 0x05u;
}

using search::attribute::Config;
using search::attribute::BasicType;
using search::attribute::CollectionType;
using search::GrowStrategy;

Config
createConfig(BasicType bt, CollectionType ct) {
    return Config(bt, ct);
}
Config
createConfig(BasicType bt, CollectionType ct, const GrowStrategy & grow) {
    return createConfig(bt, ct).setGrowStrategy(grow);
}

}

SingleValueSemiNibbleNumericAttribute::
SingleValueSemiNibbleNumericAttribute(const vespalib::string &baseFileName, const search::GrowStrategy & grow)
    : SingleValueSmallNumericAttribute(baseFileName,
                                       createConfig(BasicType::UINT2, CollectionType::SINGLE, grow),
                                       0x03u /* valueMask */,
                                       0x01u /* valueShiftShift */,
                                       4 * sizeof(Word) - 1 /* valueShiftMask */,
                                       log2bits<Word>() - 1/* wordShift */)
{
}


SingleValueNibbleNumericAttribute::
SingleValueNibbleNumericAttribute(const vespalib::string &baseFileName, const search::GrowStrategy & grow)
    : SingleValueSmallNumericAttribute(baseFileName,
                                       createConfig(BasicType::UINT4, CollectionType::SINGLE, grow),
                                       0x0fu /* valueMask */,
                                       0x02u /* valueShiftShift */,
                                       2 * sizeof(Word) - 1 /* valueShiftMask */,
                                       log2bits<Word>() - 2/* wordShift */)
{
}

}
