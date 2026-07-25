package crabcraft.net.crabUtilities.jade.protocol.provider;

import crabcraft.net.crabUtilities.jade.protocol.accessor.Accessor;
import crabcraft.net.crabUtilities.jade.protocol.util.ViewGroup;

import java.util.List;

public interface ServerExtensionProvider<T> extends JadeProvider {
    List<ViewGroup<T>> getGroups(Accessor<?> request);
}