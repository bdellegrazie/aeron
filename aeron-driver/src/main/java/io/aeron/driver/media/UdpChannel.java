/*
 * Copyright 2014-2019 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.driver.media;

import io.aeron.ChannelUri;
import io.aeron.CommonContext;
import io.aeron.driver.exceptions.InvalidChannelException;
import org.agrona.BitUtil;

import java.net.*;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static io.aeron.driver.media.NetworkUtil.*;
import static java.lang.System.lineSeparator;
import static java.net.InetAddress.getByAddress;

/**
 * The media configuration for Aeron UDP channels as an instantiation of the socket addresses for a {@link ChannelUri}.
 *
 * @see ChannelUri
 * @see io.aeron.ChannelUriStringBuilder
 */
public final class UdpChannel
{
    private static final byte[] HEX_TABLE =
    {
        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    private static final AtomicInteger UNIQUE_CANONICAL_FORM_VALUE = new AtomicInteger();

    private final boolean isManualControlMode;
    private final boolean isDynamicControlMode;
    private final boolean hasExplicitControl;
    private final boolean isMulticast;
    private final boolean hasMulticastTtl;
    private final boolean hasTag;
    private final int multicastTtl;
    private final long tag;
    private final InetSocketAddress remoteData;
    private final InetSocketAddress localData;
    private final InetSocketAddress remoteControl;
    private final InetSocketAddress localControl;
    private final String uriStr;
    private final String canonicalForm;
    private final NetworkInterface localInterface;
    private final ProtocolFamily protocolFamily;
    private final ChannelUri channelUri;

    private UdpChannel(final Context context)
    {
        isManualControlMode = context.isManualControlMode;
        isDynamicControlMode = context.isDynamicControlMode;
        hasExplicitControl = context.hasExplicitControl;
        isMulticast = context.isMulticast;
        hasTag = context.hasTagId;
        tag = context.tagId;
        hasMulticastTtl = context.hasMulticastTtl;
        multicastTtl = context.multicastTtl;
        remoteData = context.remoteData;
        localData = context.localData;
        remoteControl = context.remoteControl;
        localControl = context.localControl;
        uriStr = context.uriStr;
        canonicalForm = context.canonicalForm;
        localInterface = context.localInterface;
        protocolFamily = context.protocolFamily;
        channelUri = context.channelUri;
    }

    /**
     * Parse channel URI and create a {@link UdpChannel}.
     *
     * @param channelUriString to parse.
     * @return a new {@link UdpChannel} as the result of parsing.
     * @throws InvalidChannelException if an error occurs.
     */
    @SuppressWarnings("MethodLength")
    public static UdpChannel parse(final String channelUriString)
    {
        try
        {
            final ChannelUri channelUri = ChannelUri.parse(channelUriString);
            validateConfiguration(channelUri);

            InetSocketAddress endpointAddress = getEndpointAddress(channelUri);
            final InetSocketAddress explicitControlAddress = getExplicitControlAddress(channelUri);

            final String tagIdStr = channelUri.channelTag();
            final String controlMode = channelUri.get(CommonContext.MDC_CONTROL_MODE_PARAM_NAME);
            final boolean isManualControlMode = CommonContext.MDC_CONTROL_MODE_MANUAL.equals(controlMode);
            final boolean isDynamicControlMode = CommonContext.MDC_CONTROL_MODE_DYNAMIC.equals(controlMode);

            final boolean hasNoDistinguishingCharacteristic =
                null == endpointAddress && null == explicitControlAddress && null == tagIdStr;

            if (isDynamicControlMode && null == explicitControlAddress)
            {
                throw new IllegalArgumentException(
                    "explicit control expected with dynamic control mode: " + channelUriString);
            }

            if (hasNoDistinguishingCharacteristic && !isManualControlMode)
            {
                throw new IllegalArgumentException(
                    "Aeron URIs for UDP must specify an endpoint address, control address, tag-id, or control-mode: " +
                    channelUriString);
            }

            if (null != endpointAddress && endpointAddress.isUnresolved())
            {
                throw new UnknownHostException("could not resolve endpoint address: " + endpointAddress);
            }

            if (null != explicitControlAddress && explicitControlAddress.isUnresolved())
            {
                throw new UnknownHostException("could not resolve control address: " + explicitControlAddress);
            }

            final Context context = new Context()
                .uriStr(channelUriString)
                .channelUri(channelUri)
                .isManualControlMode(isManualControlMode)
                .isDynamicControlMode(isDynamicControlMode)
                .hasNoDistinguishingCharacteristic(hasNoDistinguishingCharacteristic);

            if (null != tagIdStr)
            {
                context.hasTagId(true).tagId(Long.parseLong(tagIdStr));
            }

            if (null == endpointAddress)
            {
                endpointAddress = new InetSocketAddress("0.0.0.0", 0);
            }

            if (endpointAddress.getAddress().isMulticastAddress())
            {
                final InetSocketAddress controlAddress = getMulticastControlAddress(endpointAddress);
                final InterfaceSearchAddress searchAddress = getInterfaceSearchAddress(channelUri);
                final NetworkInterface localInterface = findInterface(searchAddress);
                final InetSocketAddress resolvedAddress = resolveToAddressOfInterface(localInterface, searchAddress);

                context
                    .isMulticast(true)
                    .localControlAddress(resolvedAddress)
                    .remoteControlAddress(controlAddress)
                    .localDataAddress(resolvedAddress)
                    .remoteDataAddress(endpointAddress)
                    .localInterface(localInterface)
                    .protocolFamily(getProtocolFamily(endpointAddress.getAddress()))
                    .canonicalForm(canonicalise(resolvedAddress, endpointAddress));

                final String ttlValue = channelUri.get(CommonContext.TTL_PARAM_NAME);
                if (null != ttlValue)
                {
                    context.hasMulticastTtl(true).multicastTtl(Integer.parseInt(ttlValue));
                }
            }
            else if (null != explicitControlAddress)
            {
                context
                    .hasExplicitControl(true)
                    .remoteControlAddress(endpointAddress)
                    .remoteDataAddress(endpointAddress)
                    .localControlAddress(explicitControlAddress)
                    .localDataAddress(explicitControlAddress)
                    .protocolFamily(getProtocolFamily(endpointAddress.getAddress()))
                    .canonicalForm(canonicalise(explicitControlAddress, endpointAddress));
            }
            else
            {
                final InterfaceSearchAddress searchAddress = getInterfaceSearchAddress(channelUri);

                final InetSocketAddress localAddress = searchAddress.getInetAddress().isAnyLocalAddress() ?
                    searchAddress.getAddress() :
                    resolveToAddressOfInterface(findInterface(searchAddress), searchAddress);

                final String uniqueCanonicalFormSuffix = hasNoDistinguishingCharacteristic ?
                    ("-" + UNIQUE_CANONICAL_FORM_VALUE.getAndAdd(1)) : "";

                context
                    .remoteControlAddress(endpointAddress)
                    .remoteDataAddress(endpointAddress)
                    .localControlAddress(localAddress)
                    .localDataAddress(localAddress)
                    .protocolFamily(getProtocolFamily(endpointAddress.getAddress()))
                    .canonicalForm(canonicalise(localAddress, endpointAddress) + uniqueCanonicalFormSuffix);
            }

            return new UdpChannel(context);
        }
        catch (final Exception ex)
        {
            throw new InvalidChannelException(ex);
        }
    }

    /**
     * Return a string which is a canonical form of the channel suitable for use as a file or directory
     * name and also as a method of hashing, etc.
     * <p>
     * A canonical form:
     * <ul>
     *     <li>begins with the string "UDP-"</li>
     *     <li>has all addresses converted to hexadecimal</li>
     *     <li>uses "-" as field separator</li>
     * </ul>
     * <p>
     * The general format is:
     * UDP-interface-localPort-remoteAddress-remotePort
     *
     * @param localData  address/interface for the channel.
     * @param remoteData address for the channel.
     * @return canonical representation as a string.
     */
    public static String canonicalise(final InetSocketAddress localData, final InetSocketAddress remoteData)
    {
        final StringBuilder builder = new StringBuilder(48);

        builder.append("UDP-");

        toHex(builder, localData.getAddress().getAddress())
            .append('-')
            .append(localData.getPort());

        builder.append('-');

        toHex(builder, remoteData.getAddress().getAddress())
            .append('-')
            .append(remoteData.getPort());

        return builder.toString();
    }

    /**
     * Remote data address and port.
     *
     * @return remote data address and port.
     */
    public InetSocketAddress remoteData()
    {
        return remoteData;
    }

    /**
     * Local data address and port.
     *
     * @return local data address port.
     */
    public InetSocketAddress localData()
    {
        return localData;
    }

    /**
     * Remote control address information
     *
     * @return remote control address information
     */
    public InetSocketAddress remoteControl()
    {
        return remoteControl;
    }

    /**
     * Local control address and port.
     *
     * @return local control address and port.
     */
    public InetSocketAddress localControl()
    {
        return localControl;
    }

    /**
     * Get the {@link ChannelUri} for this channel.
     *
     * @return the {@link ChannelUri} for this channel.
     */
    public ChannelUri channelUri()
    {
        return channelUri;
    }

    /**
     * Has this channel got a multicast TTL value set so that {@link #multicastTtl()} is valid.
     *
     * @return true if this channel is a multicast TTL set otherwise false.
     */
    public boolean hasMulticastTtl()
    {
        return hasMulticastTtl;
    }

    /**
     * Multicast TTL value.
     *
     * @return multicast TTL value.
     */
    public int multicastTtl()
    {
        return multicastTtl;
    }

    /**
     * The canonical form for the channel
     * <p>
     * {@link UdpChannel#canonicalise(java.net.InetSocketAddress, java.net.InetSocketAddress)}
     *
     * @return canonical form for channel.
     */
    public String canonicalForm()
    {
        return canonicalForm;
    }

    /**
     * Channels are considered equal if the {@link #canonicalForm()} is equal.
     *
     * @param o object to be compared with.
     * @return true if the {@link #canonicalForm()} is equal, otherwise false.
     */
    public boolean equals(final Object o)
    {
        if (this == o)
        {
            return true;
        }

        if (o == null || getClass() != o.getClass())
        {
            return false;
        }

        final UdpChannel that = (UdpChannel)o;

        return Objects.equals(canonicalForm, that.canonicalForm);
    }

    /**
     * The hash code for the {@link #canonicalForm()}.
     *
     * @return the hash code for the {@link #canonicalForm()}.
     */
    public int hashCode()
    {
        return canonicalForm != null ? canonicalForm.hashCode() : 0;
    }

    /**
     * The {@link #canonicalForm()} for the channel.
     *
     * @return the {@link #canonicalForm()} for the channel.
     */
    public String toString()
    {
        return canonicalForm;
    }

    /**
     * Is the channel UDP multicast.
     *
     * @return true if the channel is UDP multicast.
     */
    public boolean isMulticast()
    {
        return isMulticast;
    }

    /**
     * Local interface to be used by the channel.
     *
     * @return {@link NetworkInterface} for the local interface used by the channel.
     */
    public NetworkInterface localInterface()
    {
        return localInterface;
    }

    /**
     * Original URI of the channel URI.
     *
     * @return the original uri string from the client.
     */
    public String originalUriString()
    {
        return uriStr;
    }

    /**
     * Get the {@link ProtocolFamily} for this channel.
     *
     * @return the {@link ProtocolFamily} for this channel.
     */
    public ProtocolFamily protocolFamily()
    {
        return protocolFamily;
    }

    /**
     * Get the tag value on the channel which is only valid if {@link #hasTag()} is true.
     *
     * @return the tag value on the channel.
     */
    public long tag()
    {
        return tag;
    }

    /**
     * Does the channel have manual control mode specified.
     *
     * @return does channel have manual control mode specified.
     */
    public boolean isManualControlMode()
    {
        return isManualControlMode;
    }

    /**
     * Does the channel have dynamic control mode specified.
     *
     * @return does channel have dynamic control mode specified.
     */
    public boolean isDynamicControlMode()
    {
        return isDynamicControlMode;
    }

    /**
     * Does the channel have an explicit control address as used with multi-destination-cast or not?
     *
     * @return does channel have an explicit control address or not?
     */
    public boolean hasExplicitControl()
    {
        return hasExplicitControl;
    }

    /**
     * Has the URI a tag to indicate entity relationships and if {@link #tag()} is valid.
     *
     * @return true if the channel has a tag.
     */
    public boolean hasTag()
    {
        return hasTag;
    }

    /**
     * Does this channel have a tag match to another channel including endpoints.
     *
     * @param udpChannel to match against.
     * @return true if there is a match otherwise false.
     */
    public boolean matchesTag(final UdpChannel udpChannel)
    {
        if (!hasTag || !udpChannel.hasTag() || tag != udpChannel.tag())
        {
            return false;
        }

        if (udpChannel.remoteData().getAddress().isAnyLocalAddress() &&
            udpChannel.remoteData().getPort() == 0 &&
            udpChannel.localData().getAddress().isAnyLocalAddress() &&
            udpChannel.localData().getPort() == 0)
        {
            return true;
        }

        throw new IllegalArgumentException(
            "matching tag has set endpoint or control address - " + uriStr + " <> " + udpChannel.uriStr);
    }

    /**
     * Get the endpoint destination address from the URI.
     *
     * @param uri to check.
     * @return endpoint address for URI.
     */
    public static InetSocketAddress destinationAddress(final ChannelUri uri)
    {
        try
        {
            validateConfiguration(uri);
            return getEndpointAddress(uri);
        }
        catch (final Exception ex)
        {
            throw new InvalidChannelException(ex);
        }
    }

    /**
     * Used for debugging to get a human readable description of the channel.
     *
     * @return a human readable description of the channel.
     */
    public String description()
    {
        final StringBuilder builder = new StringBuilder("UdpChannel - ");
        if (null != localInterface)
        {
            builder
                .append("interface: ")
                .append(localInterface.getDisplayName())
                .append(", ");
        }

        builder
            .append("localData: ").append(localData)
            .append(", remoteData: ").append(remoteData)
            .append(", ttl: ").append(multicastTtl);

        return builder.toString();
    }

    private static InetSocketAddress getMulticastControlAddress(final InetSocketAddress endpointAddress)
        throws UnknownHostException
    {
        final byte[] addressAsBytes = endpointAddress.getAddress().getAddress();
        validateDataAddress(addressAsBytes);

        addressAsBytes[addressAsBytes.length - 1]++;
        return new InetSocketAddress(getByAddress(addressAsBytes), endpointAddress.getPort());
    }

    private static InterfaceSearchAddress getInterfaceSearchAddress(final ChannelUri uri) throws UnknownHostException
    {
        final String interfaceValue = uri.get(CommonContext.INTERFACE_PARAM_NAME);
        if (null != interfaceValue)
        {
            return InterfaceSearchAddress.parse(interfaceValue);
        }

        return InterfaceSearchAddress.wildcard();
    }

    private static InetSocketAddress getEndpointAddress(final ChannelUri uri)
    {
        final String endpointValue = uri.get(CommonContext.ENDPOINT_PARAM_NAME);
        if (null != endpointValue)
        {
            return SocketAddressParser.parse(endpointValue);
        }

        return null;
    }

    private static InetSocketAddress getExplicitControlAddress(final ChannelUri uri)
    {
        final String controlValue = uri.get(CommonContext.MDC_CONTROL_PARAM_NAME);
        if (null != controlValue)
        {
            return SocketAddressParser.parse(controlValue);
        }

        return null;
    }

    private static void validateDataAddress(final byte[] addressAsBytes)
    {
        if (BitUtil.isEven(addressAsBytes[addressAsBytes.length - 1]))
        {
            throw new IllegalArgumentException("multicast data address must be odd");
        }
    }

    private static void validateConfiguration(final ChannelUri uri)
    {
        validateMedia(uri);
    }

    private static void validateMedia(final ChannelUri uri)
    {
        if (!CommonContext.UDP_MEDIA.equals(uri.media()))
        {
            throw new IllegalArgumentException("UdpChannel only supports UDP media: " + uri);
        }
    }

    private static InetSocketAddress resolveToAddressOfInterface(
        final NetworkInterface localInterface, final InterfaceSearchAddress searchAddress)
    {
        final InetAddress interfaceAddress = findAddressOnInterface(
            localInterface, searchAddress.getInetAddress(), searchAddress.getSubnetPrefix());

        if (null == interfaceAddress)
        {
            throw new IllegalStateException();
        }

        return new InetSocketAddress(interfaceAddress, searchAddress.getPort());
    }

    private static NetworkInterface findInterface(final InterfaceSearchAddress searchAddress)
        throws SocketException
    {
        final NetworkInterface[] filteredInterfaces = filterBySubnet(
            searchAddress.getInetAddress(), searchAddress.getSubnetPrefix());

        for (final NetworkInterface networkInterface : filteredInterfaces)
        {
            if (networkInterface.supportsMulticast() || networkInterface.isLoopback())
            {
                return networkInterface;
            }
        }

        throw new IllegalArgumentException(errorNoMatchingInterfaces(filteredInterfaces, searchAddress));
    }

    private static String errorNoMatchingInterfaces(
        final NetworkInterface[] filteredInterfaces, final InterfaceSearchAddress address)
        throws SocketException
    {
        final StringBuilder builder = new StringBuilder()
            .append("Unable to find multicast interface matching criteria: ")
            .append(address.getAddress())
            .append('/')
            .append(address.getSubnetPrefix());

        if (filteredInterfaces.length > 0)
        {
            builder.append(lineSeparator()).append("  Candidates:");

            for (final NetworkInterface ifc : filteredInterfaces)
            {
                builder
                    .append(lineSeparator())
                    .append("  - Name: ")
                    .append(ifc.getDisplayName())
                    .append(", addresses: ")
                    .append(ifc.getInterfaceAddresses())
                    .append(", multicast: ")
                    .append(ifc.supportsMulticast());
            }
        }

        return builder.toString();
    }

    private static StringBuilder toHex(final StringBuilder builder, final byte[] bytes)
    {
        for (final byte b : bytes)
        {
            builder.append((char)(HEX_TABLE[(b >> 4) & 0x0F]));
            builder.append((char)(HEX_TABLE[b & 0x0F]));
        }

        return builder;
    }

    static class Context
    {
        long tagId;
        int multicastTtl;
        InetSocketAddress remoteData;
        InetSocketAddress localData;
        InetSocketAddress remoteControl;
        InetSocketAddress localControl;
        String uriStr;
        String canonicalForm;
        NetworkInterface localInterface;
        ProtocolFamily protocolFamily;
        ChannelUri channelUri;
        boolean isManualControlMode = false;
        boolean isDynamicControlMode = false;
        boolean hasExplicitControl = false;
        boolean isMulticast = false;
        boolean hasMulticastTtl = false;
        boolean hasTagId = false;
        boolean hasNoDistinguishingCharacteristic = false;

        Context uriStr(final String uri)
        {
            uriStr = uri;
            return this;
        }

        Context remoteDataAddress(final InetSocketAddress remoteData)
        {
            this.remoteData = remoteData;
            return this;
        }

        Context localDataAddress(final InetSocketAddress localData)
        {
            this.localData = localData;
            return this;
        }

        Context remoteControlAddress(final InetSocketAddress remoteControl)
        {
            this.remoteControl = remoteControl;
            return this;
        }

        Context localControlAddress(final InetSocketAddress localControl)
        {
            this.localControl = localControl;
            return this;
        }

        Context canonicalForm(final String canonicalForm)
        {
            this.canonicalForm = canonicalForm;
            return this;
        }

        Context localInterface(final NetworkInterface networkInterface)
        {
            this.localInterface = networkInterface;
            return this;
        }

        Context protocolFamily(final ProtocolFamily protocolFamily)
        {
            this.protocolFamily = protocolFamily;
            return this;
        }

        Context hasMulticastTtl(final boolean hasMulticastTtl)
        {
            this.hasMulticastTtl = hasMulticastTtl;
            return this;
        }

        Context multicastTtl(final int multicastTtl)
        {
            this.multicastTtl = multicastTtl;
            return this;
        }

        Context tagId(final long tagId)
        {
            this.tagId = tagId;
            return this;
        }

        Context channelUri(final ChannelUri channelUri)
        {
            this.channelUri = channelUri;
            return this;
        }

        Context isManualControlMode(final boolean isManualControlMode)
        {
            this.isManualControlMode = isManualControlMode;
            return this;
        }

        Context isDynamicControlMode(final boolean isDynamicControlMode)
        {
            this.isDynamicControlMode = isDynamicControlMode;
            return this;
        }

        Context hasExplicitControl(final boolean hasExplicitControl)
        {
            this.hasExplicitControl = hasExplicitControl;
            return this;
        }

        Context isMulticast(final boolean isMulticast)
        {
            this.isMulticast = isMulticast;
            return this;
        }

        Context hasTagId(final boolean hasTagId)
        {
            this.hasTagId = hasTagId;
            return this;
        }

        Context hasNoDistinguishingCharacteristic(final boolean hasNoDistinguishingCharacteristic)
        {
            this.hasNoDistinguishingCharacteristic = hasNoDistinguishingCharacteristic;
            return this;
        }
    }
}
