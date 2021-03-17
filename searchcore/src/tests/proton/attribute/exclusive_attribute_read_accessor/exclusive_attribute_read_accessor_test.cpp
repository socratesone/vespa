// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include <vespa/vespalib/testkit/testapp.h>

#include <vespa/searchcore/proton/attribute/exclusive_attribute_read_accessor.h>
#include <vespa/searchcommon/attribute/config.h>
#include <vespa/searchlib/attribute/attributefactory.h>
#include <vespa/searchlib/attribute/attributevector.h>
#include <vespa/vespalib/util/sequencedtaskexecutor.h>
#include <vespa/vespalib/util/gate.h>

using namespace proton;
using namespace search;
using namespace vespalib;

using ReadGuard = ExclusiveAttributeReadAccessor::Guard;
VESPA_THREAD_STACK_TAG(test_executor)

AttributeVector::SP
createAttribute()
{
    attribute::Config cfg(attribute::BasicType::INT32, attribute::CollectionType::SINGLE);
    return search::AttributeFactory::createAttribute("myattr", cfg);
}

struct Fixture
{
    AttributeVector::SP attribute;
    std::unique_ptr<ISequencedTaskExecutor> writer;
    ExclusiveAttributeReadAccessor accessor;

    Fixture()
        : attribute(createAttribute()),
          writer(SequencedTaskExecutor::create(test_executor, 1)),
          accessor(attribute, *writer)
    {}
};

TEST_F("require that attribute write thread is blocked while guard is held", Fixture)
{
    ReadGuard::UP guard = f.accessor.takeGuard();
    Gate gate;
    f.writer->execute(f.writer->getExecutorIdFromName(f.attribute->getNamePrefix()), [&gate]() { gate.countDown(); });
    bool reachedZero = gate.await(100ms);
    EXPECT_FALSE(reachedZero);
    EXPECT_EQUAL(1u, gate.getCount());

    guard.reset();
    gate.await();
    EXPECT_EQUAL(0u, gate.getCount());
}

TEST_MAIN()
{
    TEST_RUN_ALL();
}
