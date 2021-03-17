// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <vespa/searchlib/attribute/stringbase.h>
#include <vespa/searchlib/attribute/enumattribute.h>
#include <vespa/searchlib/attribute/enumstore.h>
#include <vespa/searchlib/attribute/multienumattribute.h>
#include <vespa/searchlib/attribute/multi_value_mapping.h>
#include "enumhintsearchcontext.h"
#include "multivalue.h"

namespace search {

/**
 * Implementation of multi value string attribute that uses an underlying enum store
 * to store unique string values and a multi value mapping to store the enum store indices
 * for each document.
 * This class is used for both array and weighted set types.
 *
 * B: Base class: EnumAttribute<StringAttribute>
 * M: multivalue::Value<IEnumStore::Index> (array) or
 *    multivalue::WeightedValue<IEnumStore::Index> (weighted set)
 */
template <typename B, typename M>
class MultiValueStringAttributeT : public MultiValueEnumAttribute<B, M> {
protected:
    using DocIndices = typename MultiValueAttribute<B, M>::DocumentValues;
    using EnumHintSearchContext = attribute::EnumHintSearchContext;
    using EnumIndex = typename MultiValueAttribute<B, M>::ValueType;
    using EnumStore = typename B::EnumStore;
    using MultiValueMapping = typename MultiValueAttribute<B, M>::MultiValueMapping;
    using QueryTermSimpleUP = AttributeVector::QueryTermSimpleUP;
    using WeightedIndex = typename MultiValueAttribute<B, M>::MultiValueType;
    using WeightedIndexArrayRef = typename MultiValueAttribute<B, M>::MultiValueArrayRef;

    using Change = StringAttribute::Change;
    using ChangeVector = StringAttribute::ChangeVector;
    using DocId = StringAttribute::DocId;
    using EnumHandle = StringAttribute::EnumHandle;
    using LoadedVector = StringAttribute::LoadedVector;
    using SearchContext = StringAttribute::SearchContext;
    using ValueModifier = StringAttribute::ValueModifier;
    using WeightedConstChar = StringAttribute::WeightedConstChar;
    using WeightedEnum = StringAttribute::WeightedEnum;
    using WeightedString = StringAttribute::WeightedString;
    using generation_t = StringAttribute::generation_t;

public:
    MultiValueStringAttributeT(const vespalib::string & name, const AttributeVector::Config & c =
                              AttributeVector::Config(AttributeVector::BasicType::STRING,
                                                      attribute::CollectionType::ARRAY));
    ~MultiValueStringAttributeT();

    void freezeEnumDictionary() override;

    //-------------------------------------------------------------------------
    // new read api
    //-------------------------------------------------------------------------
    const char * get(DocId doc) const  override {
        WeightedIndexArrayRef indices(this->_mvMapping.get(doc));
        if (indices.size() == 0) {
            return NULL;
        } else {
            return this->_enumStore.get_value(indices[0].value());
        }
    }

    std::vector<EnumHandle> findFoldedEnums(const char *value) const override {
        return this->_enumStore.find_folded_enums(value);
    }

    const char * getStringFromEnum(EnumHandle e) const override {
        return this->_enumStore.get_value(e);
    }
    template <typename BufferType>
    uint32_t getHelper(DocId doc, BufferType * buffer, uint32_t sz) const {
        WeightedIndexArrayRef indices(this->_mvMapping.get(doc));
        uint32_t valueCount = indices.size();
        for(uint32_t i = 0, m = std::min(sz, valueCount); i < m; i++) {
            buffer[i] = this->_enumStore.get_value(indices[i].value());
        }
        return valueCount;
    }
    uint32_t get(DocId doc, vespalib::string * v, uint32_t sz) const override {
        return getHelper(doc, v, sz);
    }
    uint32_t get(DocId doc, const char ** v, uint32_t sz) const override {
        return getHelper(doc, v, sz);
    }

    /// Weighted interface
    template <typename WeightedType>
    uint32_t getWeightedHelper(DocId doc, WeightedType * buffer, uint32_t sz) const {
        WeightedIndexArrayRef indices(this->_mvMapping.get(doc));
        uint32_t valueCount = indices.size();
        for (uint32_t i = 0, m = std::min(sz, valueCount); i < m; ++i) {
            buffer[i] = WeightedType(this->_enumStore.get_value(indices[i].value()), indices[i].weight());
        }
        return valueCount;
    }
    uint32_t get(DocId doc, WeightedString * v, uint32_t sz) const override {
        return getWeightedHelper(doc, v, sz);
    }
    uint32_t get(DocId doc, WeightedConstChar * v, uint32_t sz) const override {
        return getWeightedHelper(doc, v, sz);
    }

    /*
     * Specialization of SearchContext for weighted set type
     */
    class StringImplSearchContext : public StringAttribute::StringSearchContext {
    public:
        StringImplSearchContext(QueryTermSimpleUP qTerm, const StringAttribute & toBeSearched) :
            StringAttribute::StringSearchContext(std::move(qTerm), toBeSearched)
        { }
    protected:
        const MultiValueStringAttributeT<B, M> & myAttribute() const {
            return static_cast< const MultiValueStringAttributeT<B, M> & > (attribute());
        }
        int32_t onFind(DocId docId, int32_t elemId) const override;

        template <typename Collector>
        int32_t findNextWeight(DocId doc, int32_t elemId, int32_t & weight, Collector & collector) const;
    };

    /*
     * Specialization of SearchContext for weighted set type
     */
    class StringSetImplSearchContext : public StringImplSearchContext {
    public:
        StringSetImplSearchContext(SearchContext::QueryTermSimpleUP qTerm, const StringAttribute & toBeSearched) :
            StringImplSearchContext(std::move(qTerm), toBeSearched)
        { }
    protected:
        int32_t onFind(DocId docId, int32_t elemId, int32_t &weight) const override;
    };

    /*
     * Specialization of SearchContext for array type
     */
    class StringArrayImplSearchContext : public StringImplSearchContext {
    public:
        StringArrayImplSearchContext(SearchContext::QueryTermSimpleUP qTerm, const StringAttribute & toBeSearched) :
            StringImplSearchContext(std::move(qTerm), toBeSearched)
        { }
    protected:
        int32_t onFind(DocId docId, int32_t elemId, int32_t &weight) const override;
    };

    template <typename BT>
    class StringTemplSearchContext : public BT,
                                     public EnumHintSearchContext
    {
        using BT::queryTerm;
        using AttrType = MultiValueStringAttributeT<B, M>;
    public:
        StringTemplSearchContext(SearchContext::QueryTermSimpleUP qTerm, const AttrType & toBeSearched);
    };

    SearchContext::UP
    getSearch(QueryTermSimpleUP term, const attribute::SearchContextParams & params) const override;
};


using ArrayStringAttribute = MultiValueStringAttributeT<EnumAttribute<StringAttribute>, multivalue::Value<IEnumStore::Index> >;
using WeightedSetStringAttribute = MultiValueStringAttributeT<EnumAttribute<StringAttribute>, multivalue::WeightedValue<IEnumStore::Index> >;

}
