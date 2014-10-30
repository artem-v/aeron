
#include <gtest/gtest.h>
#include <concurrent/AtomicBuffer.h>
#include <command/ConnectionMessageFlyweight.h>

using namespace aeron::common::command;
using namespace aeron::common::concurrent;

static std::array<std::uint8_t, 1024> testBuffer;

static void clearBuffer()
{
    testBuffer.fill(0);
}

TEST (commandTests, testConnectionMessageFlyweight)
{
    clearBuffer();
    AtomicBuffer ab (&testBuffer[0], testBuffer.size());
    const size_t BASEOFFSET = 256;

    std::string channelData = "channelData";

    ASSERT_NO_THROW({
        ConnectionMessageFlyweight cmd (ab, BASEOFFSET);
        cmd.correlationId(1).sessionId(2).streamId(3).channel(channelData);

        ASSERT_EQ(cmd.correlationId(), 1);
        ASSERT_EQ(cmd.sessionId(), 2);
        ASSERT_EQ(cmd.streamId(), 3);
        ASSERT_EQ(cmd.channel(), channelData);

        ASSERT_EQ(cmd.length(), offsetof(ConnectionMessageDefn, channel.channelData) + channelData.length());
    });
}
