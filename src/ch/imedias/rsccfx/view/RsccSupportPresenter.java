package ch.imedias.rsccfx.view;

import ch.imedias.rsccfx.ControlledPresenter;
import ch.imedias.rsccfx.ViewController;
import ch.imedias.rsccfx.model.Rscc;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Scene;
import javafx.scene.image.Image;

/**
 * Presenter class of RsccSupportView. Defines the behaviour of interactions
 * and initializes the size of the GUI components.
 * The supporter can enter the key given from the help requester to establish a connection.
 */
public class RsccSupportPresenter implements ControlledPresenter {
  private static final double WIDTH_SUBTRACTION_ENTERKEY = 80d;
  private final Image validImage =
      new Image(getClass().getClassLoader().getResource("emblem-default.png").toExternalForm());
  private final Image invalidImage =
      new Image(getClass().getClassLoader().getResource("dialog-error.png").toExternalForm());

  private final BooleanProperty keyValidityProperty = new SimpleBooleanProperty(false);

  private final Rscc model;
  private final RsccSupportView view;
  private final HeaderPresenter headerPresenter;
  private ViewController viewParent;

  /**
   * Initializes a new RsccSupportPresenter with the according view.
   */
  public RsccSupportPresenter(Rscc model, RsccSupportView view) {
    this.model = model;
    this.view = view;
    headerPresenter = new HeaderPresenter(model, view.headerView);
    attachEvents();
    initHeader();
    initBindings();
  }

  /**
   * Defines the ViewController to allow changing of views.
   */
  public void setViewParent(ViewController viewParent) {
    this.viewParent = viewParent;
  }

  /**
   * Initializes the size of the whole RsccSupportView elements.
   *
   * @param scene must be initialized and displayed before calling this method;
   *              The size of all header elements are based on it.
   * @throws NullPointerException if called before this object is fully initialized.
   */
  public void initSize(Scene scene) {
    // initialize header
    headerPresenter.initSize(scene);

    // initialize view
    view.enterKeyLbl.prefWidthProperty().bind(scene.widthProperty()
        .subtract(WIDTH_SUBTRACTION_ENTERKEY));
  }

  /**
   * Determines if a key is valid or not.
   * The key must not be null and must be a number with exactly 9 digits.
   */
  private boolean validateKey(String key) {
    return key != null && key.matches("\\d{9}");
  }

  /**
   * Updates the validation image after every key pressed.
   */
  private void attachEvents() {
    // update keyValidityProperty property every time the textfield with the key changes
    view.keyFld.textProperty().addListener(
        (observable, oldKey, newKey) -> keyValidityProperty.set(validateKey(newKey))
    );

    view.connectBtn.setOnAction(event -> {
      model.setKey(view.keyFld.getText());
      model.connectToUser();
    });

    // Closes the other TitledPane so that just one TitledPane is shown on the screen.
    view.keyInputPane.setOnMouseClicked(event -> view.predefinedAdressesPane.setExpanded(false));
    view.predefinedAdressesPane.setOnMouseClicked(event -> view.keyInputPane.setExpanded(false));
  }

  private void initBindings() {
    // disable connect button if key is NOT valid
    view.connectBtn.disableProperty().bind(keyValidityProperty.not());

    // bind validation image to keyValidityProperty
    view.isValidImg.imageProperty().bind(
        Bindings.when(keyValidityProperty)
            .then(validImage)
            .otherwise(invalidImage)
    );
  }

  /**
   * Initializes the functionality of the header, e.g. back and settings button.
   */
  private void initHeader() {
    // Set all the actions regarding buttons in this method.
    headerPresenter.setBackBtnAction(event -> viewParent.setView("home"));
    // TODO: Set actions on buttons (Help, Settings)
  }

}
