/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
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
package dev.ikm.komet.amplify.mvvm.loader;

import dev.ikm.komet.amplify.mvvm.ViewModel;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.*;

/**
 * An FXML loader capable of injecting ViewModels into Controller classes.
 * Returns a JFXNode object containing the Node, Controller and set of NamedVm objects.
 * NamedVm objects represent a variable name and view model. This allows the caller to inject many view models into a
 * controller. Since they are created inside a controller the loader will return the known set of variables and their view models.
 *
 * The following are the various use cases.
 * <pre>
 *     1. Controller has a field annotated with @InjectViewModel instantiate the view model to be assigned.
 *     2. Controller receives a NamedVm to be referenced instead of instantiating a view model.
 *     3. Caller supplies a newly instantiated instance of a Controller to be passed into make().
 *     4. Caller supplies a controller class to be passed into make().
 *     5. FXML specifies a controller class. Caller will supply no controller into make().
 * </pre>
 */
public class FXMLMvvmLoader {
    private static final Logger LOG = LoggerFactory.getLogger(FXMLMvvmLoader.class);
    public static JFXNode make(URL fxml, NamedVm ...namedViewModels) {
        return make(new Config(fxml), namedViewModels);
    }
    public static JFXNode make(URL fxml, Class controllerClass, NamedVm ...namedViewModels) {
        return make(new Config(fxml, controllerClass), namedViewModels);
    }
    public static JFXNode make(URL fxml, Object controller, NamedVm ...namedViewModels) {
        return make(new Config(fxml, controller), namedViewModels);
    }
    public static JFXNode make(Config config, List<NamedVm> namedViewModels) {
        NamedVm[] namedVms = new NamedVm[namedViewModels.size()];
        namedViewModels.toArray(namedVms); // fill the array
        return make(config, namedVms);
    }
    public static JFXNode make(Config config, NamedVm ...namedViewModels) {
        Map<String, NamedVm> namedViewModelMap = new TreeMap<>();
        // scan from config
        if (config.namedViewModels() != null && config.namedViewModels().length > 0) {
            for(NamedVm namedVm: config.namedViewModels()) {
                namedViewModelMap.putIfAbsent(namedVm.variableName(), namedVm);
            }
        }

        // scan from namedViewModels array
        if (namedViewModels != null && namedViewModels.length > 0) {
            for(NamedVm namedVm: namedViewModels) {
                namedViewModelMap.putIfAbsent(namedVm.variableName(), namedVm);
            }
        }

        Object controller = null;
        if (config.controllerClass() != null && config.controller() == null) {
            try {
                controller = config.controllerClass().getDeclaredConstructor().newInstance();
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        } else if (config.controller() != null) {
            controller = config.controller();
        } else {
            // TODO: FXML may have an associated controllerClass. TODO look at ControllerFactory in fxmlloader.

        }

        Node node = null;
        try {
            FXMLLoader loader = new FXMLLoader(config.fxml());
            final Set<NamedVm> namedVms = new LinkedHashSet<>(); // add order
            // When a controller is set inside an FXML file. This creates a controller factory.
            // Here the code will inject the view models.
            if (controller == null) {
                loader.setControllerFactory(aClass -> {
                    try {
                        if (aClass != null) {
                            Object controllerInFxml = aClass.getDeclaredConstructor().newInstance();
                            namedVms.addAll(injectViewModels(controllerInFxml, namedViewModelMap));
                            return controllerInFxml;
                        }
                        return null;
                    } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                        throw new RuntimeException(e);
                    }
                });
            } else {
                // When caller is supplying a controller class or controller instance.
                if (controller != null) {
                    // also get a list of ViewModels
                    namedVms.addAll(injectViewModels(controller, namedViewModelMap));
                    LOG.info("Injecting ViewModels into controller class %s with the following fields: %s".formatted(controller.getClass().getName(), namedVms));
                    loader.setController(controller);
                }
            }

            node = loader.load();
            controller = loader.getController();

            return new JFXNode(node, controller, namedVms);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
    protected static List<NamedVm> injectViewModels(Object controller, Map<String, NamedVm> namedViewModelMap) {
        List<NamedVm> names = new ArrayList<>();
        for (Field field : controller.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(InjectViewModel.class)) {

                field.setAccessible(true);
                try {
                    Object viewModel = field.get(controller);
                    String fieldName = field.getName();
                    if (viewModel == null) {
                        if (namedViewModelMap.containsKey(fieldName)) {
                            // replace from map to be injected
                            NamedVm namedVm = namedViewModelMap.get(fieldName);
                            names.add(namedVm);
                            field.set(controller, namedVm.viewModel());
                        } else {
                            // create a new empty constructor of the ViewModel's type.
                            viewModel = field.getType().getDeclaredConstructor().newInstance();
                            names.add(new NamedVm(fieldName, (ViewModel) viewModel));
                            field.set(controller, viewModel);
                        }
                    } else {
                        // The user has chosen to instanciate field.
                    }
                } catch (IllegalAccessException | NoSuchMethodException | InstantiationException |
                         InvocationTargetException e) {
                    throw new RuntimeException("%s class field %s".formatted(),e);
                }
            }
        }
        return names;
    }
}